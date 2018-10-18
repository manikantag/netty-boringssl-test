package com.manikanta;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ApnsTest {

    @Test
    public void test(TestContext context) throws Exception {
        Apns.sendAPNSPushUsingCertificate("path/to/cert",
                                          "cert-password",
                                          false);
    }
}
