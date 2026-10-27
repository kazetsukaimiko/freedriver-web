package io.freedriver.app.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.app.appliances.MqttSettings;
import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttEndpoint;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.paho.PahoMqttConnection;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Creates one autonomy user on the dynamic-security broker and grants the api user that house.
 * The admin client is server configuration. This type does not return that secret.
 */
@ApplicationScoped
@Unremovable
public class HouseProvisioner {

    static final String API_ROLE = "api";
    static final String CONTROL_TOPIC = DynsecControl.COMMAND_TOPIC;
    static final String CONTROL_RESPONSE_TOPIC = DynsecControl.RESPONSE_TOPIC;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MqttSettings mqtt;

    @Inject
    public HouseProvisioner(MqttSettings mqtt) {
        this.mqtt = mqtt;
    }

    /**
     * Provisions {@code houseId}. The returned secret is the new autonomy user's password.
     * Fails when the admin login is missing or is Mosquitto's default admin user.
     */
    public ProvisionedHouse provision(UUID houseId) {
        String adminPassword = firstSecret(mqtt.dynsecPassword().orElse(""), mqtt.dynsecPasswordFile().orElse(""));
        String apiPassword = firstSecret(mqtt.password().orElse(""), mqtt.passwordFile().orElse(""));
        return provisionHouse(
                houseId,
                mqtt.host(),
                mqtt.port(),
                mqtt.tls(),
                Path.of(mqtt.trustCertificate()),
                mqtt.dynsecUsername().orElse(""),
                adminPassword,
                mqtt.username(),
                apiPassword);
    }

    /** Inline secret wins. Otherwise the host file, which is not logged. */
    private static String firstSecret(String inline, String file) {
        String value = inline == null ? "" : inline.strip();
        if (!value.isEmpty()) {
            return value;
        }
        if (file == null || file.isBlank()) {
            return "";
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path).strip();
        } catch (IOException e) {
            MqttLog.configUnreadable("dynsec-pass");
            return "";
        }
    }

    static ProvisionedHouse provisionHouse(
            UUID houseId,
            String host,
            int port,
            boolean tls,
            Path trustCertificate,
            String adminUsername,
            String adminPassword,
            String apiUsername,
            String apiPassword) {
        if (houseId == null) {
            throw new IllegalArgumentException("house id is required");
        }
        MqttBrokers.assertLiveBroker(host, port, tls);
        rejectDefaultAdmin(adminUsername, adminPassword);
        if (apiUsername == null || apiUsername.isBlank() || apiPassword == null || apiPassword.isBlank()) {
            throw new IllegalStateException("api broker password is not configured");
        }
        if (adminUsername.equals(apiUsername) || "admin".equalsIgnoreCase(apiUsername)) {
            throw new IllegalStateException("api user must not be the dynamic security admin");
        }
        String autonomyUsername = autonomyUsername(houseId);
        if (autonomyUsername.equals(adminUsername) || autonomyUsername.equals(apiUsername)) {
            throw new IllegalStateException("autonomy username collides with a broker login");
        }
        String appliances = ApplianceSchemas.appliancesTopic(houseId);
        String commands = ApplianceSchemas.commandsTopic(houseId);
        MqttBrokers.assertExactTopic(appliances);
        MqttBrokers.assertExactTopic(commands);
        String brokerSecret = newSecret();
        MqttEndpoint admin = new MqttEndpoint(host, port, tls, adminUsername);
        PahoMqttConnection connection = new PahoMqttConnection(admin, adminPassword, trustCertificate);
        try (DynsecControl dynsec = new DynsecControl(connection)) {
            dynsec.start();
            createAutonomy(dynsec, autonomyUsername, brokerSecret, appliances, commands);
            grantApi(dynsec, apiUsername, apiPassword, appliances, commands);
        }
        return new ProvisionedHouse(houseId, autonomyUsername, brokerSecret);
    }

    static String autonomyUsername(UUID houseId) {
        return "autonomy-" + houseId;
    }

    private static void createAutonomy(
            DynsecControl dynsec,
            String username,
            String brokerSecret,
            String appliances,
            String commands) {
        String role = username;
        ObjectNode createRole = command("createRole");
        createRole.put("rolename", role);
        createRole.put("allowwildcardsubs", false);
        ArrayNode acls = createRole.putArray("acls");
        allow(acls, "publishClientSend", appliances);
        allow(acls, "subscribeLiteral", commands);
        allow(acls, "publishClientReceive", commands);
        denyControl(acls);
        requireOk(dynsec.command(createRole), "autonomy role was not created");

        ObjectNode createClient = command("createClient");
        createClient.put("username", username);
        createClient.put("password", brokerSecret);
        createClient.putArray("roles").addObject().put("rolename", role);
        requireOk(dynsec.command(createClient), "autonomy user was not created");
    }

    private static void grantApi(
            DynsecControl dynsec,
            String apiUsername,
            String apiPassword,
            String appliances,
            String commands) {
        JsonNode role = dynsec.command(command("getRole").put("rolename", API_ROLE));
        if (isError(role, "Role not found")) {
            ObjectNode createRole = command("createRole");
            createRole.put("rolename", API_ROLE);
            createRole.put("allowwildcardsubs", false);
            denyControl(createRole.putArray("acls"));
            requireOk(dynsec.command(createRole), "api role was not created");
        } else {
            requireOk(role, "api role was not read");
            denyControlCommands(dynsec);
        }
        addAcl(dynsec, API_ROLE, "subscribeLiteral", appliances, true);
        addAcl(dynsec, API_ROLE, "publishClientReceive", appliances, true);
        addAcl(dynsec, API_ROLE, "publishClientSend", commands, true);

        JsonNode client = dynsec.command(command("getClient").put("username", apiUsername));
        if (isError(client, "Client not found")) {
            ObjectNode createClient = command("createClient");
            createClient.put("username", apiUsername);
            createClient.put("password", apiPassword);
            createClient.putArray("roles").addObject().put("rolename", API_ROLE);
            requireOk(dynsec.command(createClient), "api user was not created");
            return;
        }
        requireOk(client, "api user was not read");
        if (!clientHasRole(client, API_ROLE)) {
            ObjectNode addRole = command("addClientRole");
            addRole.put("username", apiUsername);
            addRole.put("rolename", API_ROLE);
            requireOk(dynsec.command(addRole), "api user was not granted the api role");
        }
    }

    private static void denyControlCommands(DynsecControl dynsec) {
        addAcl(dynsec, API_ROLE, "publishClientSend", CONTROL_TOPIC, false);
        addAcl(dynsec, API_ROLE, "publishClientSend", CONTROL_RESPONSE_TOPIC, false);
        addAcl(dynsec, API_ROLE, "subscribeLiteral", CONTROL_TOPIC, false);
        addAcl(dynsec, API_ROLE, "subscribeLiteral", CONTROL_RESPONSE_TOPIC, false);
    }

    private static void denyControl(ArrayNode acls) {
        deny(acls, "publishClientSend", CONTROL_TOPIC);
        deny(acls, "publishClientSend", CONTROL_RESPONSE_TOPIC);
        deny(acls, "subscribeLiteral", CONTROL_TOPIC);
        deny(acls, "subscribeLiteral", CONTROL_RESPONSE_TOPIC);
    }

    private static void allow(ArrayNode acls, String acltype, String topic) {
        acl(acls, acltype, topic, true, 0);
    }

    private static void deny(ArrayNode acls, String acltype, String topic) {
        acl(acls, acltype, topic, false, 100);
    }

    private static void acl(ArrayNode acls, String acltype, String topic, boolean allow, int priority) {
        MqttBrokers.assertExactTopic(topic);
        ObjectNode acl = acls.addObject();
        acl.put("acltype", acltype);
        acl.put("topic", topic);
        acl.put("allow", allow);
        acl.put("priority", priority);
    }

    private static void addAcl(DynsecControl dynsec, String role, String acltype, String topic, boolean allow) {
        ObjectNode command = command("addRoleACL");
        command.put("rolename", role);
        command.put("acltype", acltype);
        command.put("topic", topic);
        command.put("allow", allow);
        command.put("priority", allow ? 0 : 100);
        JsonNode response = dynsec.command(command);
        if (isError(response, "ACL with this topic already exists")) {
            return;
        }
        requireOk(response, "role ACL was not granted");
    }

    private static boolean clientHasRole(JsonNode client, String role) {
        JsonNode roles = client.path("data").path("client").path("roles");
        if (!roles.isArray()) {
            return false;
        }
        for (JsonNode item : roles) {
            if (role.equals(item.path("rolename").asText())) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode command(String name) {
        ObjectNode command = JSON.createObjectNode();
        command.put("command", name);
        return command;
    }

    private static void requireOk(JsonNode response, String failure) {
        JsonNode error = response.get("error");
        if (error != null && !error.isNull() && !error.asText().isBlank()) {
            throw new IllegalStateException(failure + ": " + error.asText());
        }
    }

    private static boolean isError(JsonNode response, String expected) {
        JsonNode error = response.get("error");
        return error != null && !error.isNull() && expected.equals(error.asText());
    }

    private static void rejectDefaultAdmin(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("dynamic security admin is not configured");
        }
        if ("admin".equalsIgnoreCase(username) || "admin".equals(password)) {
            throw new IllegalStateException("dynamic security admin login must not be the default");
        }
    }

    static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
