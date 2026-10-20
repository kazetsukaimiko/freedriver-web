package io.freedriver.mqtt.paho;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Trust store for this MQTT connection only.
 * The file is a CA certificate. The private key is rejected and never logged.
 */
final class MqttTrust {

    private MqttTrust() {
    }

    static SSLSocketFactory socketFactory(Path certificate) {
        try {
            KeyStore store = trustStore(certificate);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            // init(null) would use the JVM default store. This store is the CA file only.
            trustManagers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MQTT trust certificate could not be read: " + certificate, e);
        }
    }

    static KeyStore trustStore(Path certificate) {
        byte[] pem = readCertificate(certificate);
        if (containsAscii(pem, "PRIVATE KEY")) {
            throw new IllegalStateException("MQTT trust certificate must not contain a private key");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = factory.generateCertificates(new ByteArrayInputStream(pem));
            if (certificates.isEmpty()) {
                throw new IllegalStateException("MQTT trust certificate file contained no certificates: " + certificate);
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (Certificate entry : certificates) {
                if (!(entry instanceof X509Certificate)) {
                    throw new IllegalStateException("MQTT trust certificate file contained a non-X.509 certificate");
                }
                store.setCertificateEntry("mqtt-ca-" + index, entry);
                index++;
            }
            return store;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MQTT trust certificate could not be read: " + certificate, e);
        }
    }

    private static byte[] readCertificate(Path certificate) {
        if (certificate == null || certificate.toString().isBlank()) {
            throw new IllegalStateException("MQTT trust certificate path is required");
        }
        if (!Files.isRegularFile(certificate)) {
            throw new IllegalStateException("MQTT trust certificate is not a file: " + certificate);
        }
        try {
            return Files.readAllBytes(certificate);
        } catch (Exception e) {
            throw new IllegalStateException("MQTT trust certificate could not be read: " + certificate, e);
        }
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] want = needle.getBytes(StandardCharsets.US_ASCII);
        if (want.length == 0 || haystack.length < want.length) {
            return false;
        }
        for (int i = 0; i <= haystack.length - want.length; i++) {
            boolean match = true;
            for (int j = 0; j < want.length; j++) {
                if (haystack[i + j] != want[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
