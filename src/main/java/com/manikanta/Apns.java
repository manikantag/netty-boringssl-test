package com.manikanta;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PfxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Apns {

    private static final Logger LOG = LoggerFactory.getLogger(Apns.class);

    public static void main(String[] args) throws Exception {
//        System.setProperty("javax.net.debug", "ssl:handshake");
//        System.setProperty("javax.net.debug", "all");
//        System.setProperty("java.security.debug", "access");
        System.setProperty("java.net.preferIPv4Stack", "true");

        sendAPNSPushUsingCertificate(args[0], args[1], Boolean.valueOf(args[2]));
    }

    public static void sendAPNSPushUsingCertificate(String certPath,
                                                    String password,
                                                    boolean isAPNSProduction) throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Vertx vertx = Vertx.vertx();

        Buffer p12FileBuffer = vertx.fileSystem()
                                    .readFileBlocking(certPath);

        testApnsConnectivityWithCertificate(vertx,
                                            p12FileBuffer,
                                            password,
                                            isAPNSProduction)
                .setHandler(ar -> {
                    if (ar.succeeded()) {
                        System.out.println("Is test connectivity success: " + ar.result());
                        future.complete(ar.result());
                    } else {
                        ar.cause()
                          .printStackTrace();
                        future.completeExceptionally(ar.cause());
                    }
                });

        future.get(10, TimeUnit.SECONDS);
    }


    private static Future<Boolean> testApnsConnectivityWithCertificate(Vertx vertx,
                                                                       Buffer cert,
                                                                       String password,
                                                                       boolean isAppleProduction) {
        //Can't reuse http client while performing test connectivity with certificate
        HttpClient http2ClientWithCertAuth = createHTTP2Client(vertx, cert, password);

        return sendTestMessageToApnsGateway(http2ClientWithCertAuth,
                                            isAppleProduction);
    }


    private static HttpClient createHTTP2Client(Vertx vertx,
                                                Buffer cert,
                                                String password) {

        HttpClientOptions httpClientOptions = new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setSsl(true)
                .setUseAlpn(true)
                .setHttp2MultiplexingLimit(1000)
                .setHttp2MaxPoolSize(5)
                .setKeepAlive(true)
                .setIdleTimeout(30)
                .setMaxPoolSize(5)
                .setPipelining(true)
                .setTcpNoDelay(true)
                .setTcpCork(true)
                .setTcpFastOpen(true)
                .setTcpKeepAlive(true)
                .setTcpQuickAck(true)
                .setUsePooledBuffers(true)
                .setTryUseCompression(true)
                .setOpenSslEngineOptions(new OpenSSLEngineOptions());

        PfxOptions pfxOptions = new PfxOptions()
                .setValue(cert)
                .setPassword(password);

        httpClientOptions.setPfxKeyCertOptions(pfxOptions);

        return vertx.createHttpClient(httpClientOptions);
    }


    private static Future<Boolean> sendTestMessageToApnsGateway(HttpClient http2Client,
                                                                boolean isAppleProduction) {
        Future<Boolean> promise = Future.future();
        String apnsURL = isAppleProduction ? "https://api.push.apple.com:443/3/device/" : "https://api.development.push.apple.com:443/3/device/";
        http2Client
                .postAbs(apnsURL, response -> {
                    response.bodyHandler(body -> {
                        JsonObject responseJson = body.toJsonObject();
                        LOG.debug("APNS response: {}", responseJson);

                        if (response.statusCode() == 403) {
                            String reason = responseJson.getString("reason");
                            LOG.error("Connection to APNS Cloud failed: {}", reason);
                            promise.fail(reason);
                        } else {
                            http2Client.close();
                            promise.complete(true);
                        }
                    });
                })
                .exceptionHandler(ex -> {
                    LOG.error("Connection to APNS Cloud failed", ex);
                    http2Client.close();
                    promise.fail(ex);
                })
                .end("test");

        return promise;
    }

}
