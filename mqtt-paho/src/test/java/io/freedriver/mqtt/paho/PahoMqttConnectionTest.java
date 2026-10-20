package io.freedriver.mqtt.paho;

import io.freedriver.mqtt.MqttEndpoint;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PahoMqttConnectionTest {

    private static final String STORE_PASS = "changeit";
    private static final char[] STORE_PASS_CHARS = STORE_PASS.toCharArray();
    private static final String BROKER_PASSWORD = "broker-password-sentinel";
    private static final String CA_KEY_SENTINEL = "CAKEYMATERIALSENTINEL";

    private static Path temp;
    private static Issued ours;
    private static Issued otherIssuer;
    private static Issued wrongName;
    private static Issued selfSigned;

    @BeforeAll
    static void generateCa() throws Exception {
        temp = Files.createTempDirectory("mqtt-trust");
        ours = issue(temp.resolve("ours"), "mosquitto");
        otherIssuer = issue(temp.resolve("other"), "mosquitto");
        wrongName = issue(temp.resolve("wrong-name"), "mqtt.freedriver.io");
        selfSigned = selfSigned(temp.resolve("self-signed"), "mosquitto");
    }

    @AfterAll
    static void deleteMaterial() throws IOException {
        if (temp == null) {
            return;
        }
        try (var walk = Files.walk(temp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Test
    void store_holds_the_generated_ca_certificate_not_the_private_key() throws Exception {
        assertFalse(Files.exists(ours.directory().resolve("ca.p12")));
        String pem = Files.readString(ours.certificatePem());
        assertFalse(pem.contains("PRIVATE KEY"));
        assertFalse(pem.contains(CA_KEY_SENTINEL));

        KeyStore store = MqttTrust.trustStore(ours.certificatePem());
        X509Certificate ca = readPem(ours.certificatePem());
        int certificates = 0;
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            assertTrue(store.isCertificateEntry(alias), alias);
            assertFalse(store.isKeyEntry(alias), alias);
            Certificate entry = store.getCertificate(alias);
            assertNotNull(entry);
            assertEquals(ca.getSubjectX500Principal(), ((X509Certificate) entry).getSubjectX500Principal());
            certificates++;
        }
        assertEquals(1, certificates);
        assertTrue(ca.getBasicConstraints() >= 0);
        assertTrue(hasDns(leaf(ours.serverPkcs12()), "mosquitto"));
        assertFalse(leaf(ours.serverPkcs12()).getSubjectX500Principal().getName().contains("mosquitto"));
    }

    @Test
    void dns_mosquitto_from_another_issuer_fails_and_another_name_fails() throws Exception {
        assertTrue(hasDns(leaf(otherIssuer.serverPkcs12()), "mosquitto"));
        assertTrue(hasDns(leaf(wrongName.serverPkcs12()), "mqtt.freedriver.io"));
        assertFalse(hasDns(leaf(wrongName.serverPkcs12()), "mosquitto"));
        assertTrue(hasDns(leaf(selfSigned.serverPkcs12()), "mosquitto"));

        SSLContext before = SSLContext.getDefault();
        PahoMqttConnection connection = client(ours.certificatePem());
        MqttConnectOptions options = connection.clientOptions();
        assertTrue(options.isHttpsHostnameVerificationEnabled());
        assertSame(before, SSLContext.getDefault());

        SSLSocketFactory factory = (SSLSocketFactory) options.getSocketFactory();
        assertNotNull(factory);
        // Paho sets HTTPS endpoint identification when the flag above is true.
        handshake(factory, ours.serverPkcs12(), "mosquitto");
        assertThrows(SSLHandshakeException.class, () -> handshake(factory, otherIssuer.serverPkcs12(), "mosquitto"));
        assertThrows(SSLHandshakeException.class, () -> handshake(factory, wrongName.serverPkcs12(), "mosquitto"));
        assertThrows(SSLHandshakeException.class, () -> handshake(factory, selfSigned.serverPkcs12(), "mosquitto"));
        assertThrows(SSLHandshakeException.class,
                () -> handshake(before.getSocketFactory(), ours.serverPkcs12(), "mosquitto"));
        assertSame(before, SSLContext.getDefault());
        assertFalse(String.valueOf(options).contains(BROKER_PASSWORD));
    }

    @Test
    void missing_certificate_does_not_connect_or_log_the_broker_password() throws Exception {
        Path missing = temp.resolve("absent-ca.crt");
        assertFalse(Files.exists(missing));
        PahoMqttConnection connection = client(missing);
        assertFalse(Files.exists(missing));

        Logger logger = Logger.getLogger(PahoMqttConnection.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, connection::connect);
            String text = ex.toString() + " " + ex.getCause();
            assertFalse(text.contains(BROKER_PASSWORD));
            assertFalse(text.contains(CA_KEY_SENTINEL));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        for (LogRecord record : records) {
            String text = record.getMessage() + " " + record.getThrown();
            assertFalse(text.contains(BROKER_PASSWORD), text);
            assertFalse(text.contains(CA_KEY_SENTINEL), text);
        }
    }

    @Test
    void private_key_is_refused_and_not_echoed() throws Exception {
        Path pem = temp.resolve("with-key.crt");
        Files.writeString(pem, """
                -----BEGIN PRIVATE KEY-----
                CAKEYMATERIALSENTINEL
                -----END PRIVATE KEY-----
                """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> MqttTrust.socketFactory(pem));
        String text = ex.toString() + " " + ex.getCause();
        assertFalse(text.contains(CA_KEY_SENTINEL));
        assertFalse(text.contains("BEGIN PRIVATE KEY"));
    }

    @Test
    void sources_do_not_install_the_ca_on_the_jvm_default_context() throws IOException {
        Path src = Path.of("src/main/java/io/freedriver/mqtt/paho");
        try (var walk = Files.walk(src)) {
            List<Path> files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(files.isEmpty());
            for (Path file : files) {
                String text = Files.readString(file);
                assertFalse(text.contains("SSLContext.setDefault"), file.toString());
                assertFalse(text.contains("SSLContext.getDefault"), file.toString());
                assertFalse(text.contains("setKeyEntry"), file.toString());
                assertFalse(text.contains("\"mqtt.freedriver.io\""), file.toString());
                assertFalse(text.contains("setHttpsHostnameVerificationEnabled(false)"), file.toString());
            }
        }
        String connection = Files.readString(src.resolve("PahoMqttConnection.java"));
        assertTrue(connection.contains("setHttpsHostnameVerificationEnabled(true)"));
        assertTrue(connection.contains("endpoint.host()"));
        assertTrue(connection.contains("endpoint.port()"));
    }

    private static PahoMqttConnection client(Path certificate) {
        return new PahoMqttConnection(
                new MqttEndpoint("mosquitto", 8883, true, "api"),
                BROKER_PASSWORD,
                certificate);
    }

    private static void handshake(SSLSocketFactory clientFactory, Path serverPkcs12, String hostname) throws Exception {
        SSLContext serverContext = serverContext(serverPkcs12);
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5_000);
            Thread accepted = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(5_000);
                    socket.startHandshake();
                } catch (Exception ignored) {
                    // The client assertion is the result. The server sees the peer close.
                }
            }, "mqtt-trust-server");
            accepted.setDaemon(true);
            accepted.start();
            try (Socket tcp = new Socket()) {
                tcp.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 5_000);
                try (SSLSocket ssl = (SSLSocket) clientFactory.createSocket(tcp, hostname, server.getLocalPort(), true)) {
                    SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(parameters);
                    ssl.startHandshake();
                }
            } finally {
                accepted.join(5_000);
            }
        }
    }

    private static SSLContext serverContext(Path pkcs12) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pkcs12)) {
            keys.load(in, STORE_PASS_CHARS);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, STORE_PASS_CHARS);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static X509Certificate leaf(Path pkcs12) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pkcs12)) {
            keys.load(in, STORE_PASS_CHARS);
        }
        return (X509Certificate) keys.getCertificate("leaf");
    }

    private static X509Certificate readPem(Path pem) throws Exception {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static boolean hasDns(X509Certificate certificate, String name) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        for (List<?> entry : names) {
            if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0)) && name.equals(entry.get(1))) {
                return true;
            }
        }
        return false;
    }

    private static Issued issue(Path dir, String dns) throws Exception {
        Files.createDirectories(dir);
        keytool(dir,
                "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", "CN=Test Internal CA", "-ext", "bc:c",
                "-keystore", "ca.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-keypass", STORE_PASS);
        keytool(dir,
                "-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-rfc", "-file", "ca.crt");
        keytool(dir,
                "-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", "CN=not-the-san",
                "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-keypass", STORE_PASS);
        keytool(dir,
                "-certreq", "-alias", "leaf", "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-file", "leaf.csr");
        keytool(dir,
                "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-infile", "leaf.csr", "-outfile", "leaf.crt", "-rfc",
                "-validity", "365",
                "-ext", "san=dns:" + dns,
                "-ext", "bc=ca:false",
                "-ext", "eku=serverAuth");
        keytool(dir,
                "-importcert", "-alias", "ca", "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-file", "ca.crt", "-noprompt");
        keytool(dir,
                "-importcert", "-alias", "leaf", "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-file", "leaf.crt", "-noprompt");
        Files.delete(dir.resolve("ca.p12"));
        Files.delete(dir.resolve("leaf.csr"));
        return new Issued(dir, dir.resolve("ca.crt"), dir.resolve("leaf.p12"));
    }

    private static Issued selfSigned(Path dir, String dns) throws Exception {
        Files.createDirectories(dir);
        keytool(dir,
                "-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", "CN=not-the-san", "-ext", "san=dns:" + dns, "-ext", "bc=ca:false",
                "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-keypass", STORE_PASS);
        keytool(dir,
                "-exportcert", "-alias", "leaf", "-keystore", "leaf.p12", "-storetype", "PKCS12",
                "-storepass", STORE_PASS, "-rfc", "-file", "ca.crt");
        return new Issued(dir, dir.resolve("ca.crt"), dir.resolve("leaf.p12"));
    }

    private static void keytool(Path dir, String... args) throws IOException, InterruptedException {
        Path tool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("keytool exited " + code + ": " + new String(output, StandardCharsets.UTF_8));
        }
    }

    private record Issued(Path directory, Path certificatePem, Path serverPkcs12) {
    }
}
