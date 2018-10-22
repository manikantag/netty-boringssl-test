package com.manikanta.netty;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import javax.net.ssl.KeyManagerFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;

/**
 * If everything works fine, we should get `{"reason":"MissingDeviceToken"}` as the response.
 * If we get `{"reason":"MissingProviderToken"}`, then client cert auth is not working.
 */
// Example taken from: https://github.com/netty/netty/blob/4.1/example/src/main/java/io/netty/example/http2/helloworld/client/Http2Client.java
public final class Http2Client {

    public static void main(String[] args) throws Exception {

        if (args == null || args.length < 2) {
            System.out.println("Certificate path & password should be provided as 1st adn 2nd CLI arguments");
            System.exit(1);
        }

        System.setProperty("javax.net.debug", "all");
        System.setProperty("java.net.preferIPv4Stack", "true");

        String host = "api.development.push.apple.com";
        int port = 443;
        String url = "/3/device/";
        String requestData = "test data!";

        String certPath = args[0];
        String certPass = args[1];

        // Configure SSL.
        final SslContext sslCtx;
        SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
        sslCtx = SslContextBuilder
                .forClient()
                .sslProvider(provider)
                /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                 * Please refer to the HTTP/2 specification for cipher requirements. */
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        Protocol.ALPN,
                        // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                        SelectorFailureBehavior.NO_ADVERTISE,
                        // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                        SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .keyManager(getKeyManagerFactory(certPath, certPass))
                .build();

        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE);

        try {
            // Configure the client.
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.remoteAddress(host, port);
            b.handler(initializer);

            // Start the client.
            Channel channel = b.connect()
                               .syncUninterruptibly()
                               .channel();
            System.out.println("Connected to [" + host + ':' + port + ']');

            // Wait for the HTTP/2 upgrade to occur.
            Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
            http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);

            HttpResponseHandler responseHandler = initializer.responseHandler();
            int streamId = 3;
            HttpScheme scheme = HttpScheme.HTTPS;
            AsciiString hostName = new AsciiString(host + ':' + port);
            System.err.println("Sending request(s)...");

            // Create a simple POST request with a body.
            FullHttpRequest request = new DefaultFullHttpRequest(
                    HTTP_1_1,
                    POST,
                    url,
                    wrappedBuffer(requestData.getBytes(CharsetUtil.UTF_8)));

            request.headers()
                   .add(HttpHeaderNames.HOST, hostName)
                   .add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name())
                   .add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP)
                   .add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);

            responseHandler.put(streamId, channel.write(request), channel.newPromise());

            channel.flush();

            responseHandler.awaitResponses(15, TimeUnit.SECONDS);

            System.out.println("Finished HTTP/2 request(s)");

            // Wait until the connection is closed.
            channel.close()
                   .syncUninterruptibly();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }

    private static KeyManagerFactory getKeyManagerFactory(String certPath, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(Files.newInputStream(Paths.get(certPath)), password.toCharArray());
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password.toCharArray());
        return factory;
    }

}