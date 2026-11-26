package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.ApplianceCommandRequest;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.dashboard.model.api.ApplianceCommandListRequest;
import io.freedriver.dashboard.model.api.ApplianceCommandResult;
import io.freedriver.dashboard.model.api.PendingApplianceCommand;
import io.freedriver.dashboard.model.ScheduledSwitchRequest;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.CanonicalUuid;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceCommandsApiTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final UUID HOUSE = UUID.fromString("00000000-0000-4000-8000-000000000009");
    private static final String CANONICAL = "00000000-0000-4000-8000-000000000001";

    @Test
    void routesStayOffTheLegacyApplianceTemplate() throws Exception {
        assertEquals("/api/appliance-commands", ApplianceCommandsApi.class.getAnnotation(Path.class).value());
        String legacy = path(AppliancesApi.class) + path(AppliancesApi.class.getMethod(
                "command", UUID.class, String.class, ApplianceCommandRequest.class));
        assertEquals("/api/appliances/{instanceId}/{applianceName}", legacy);
        String list = path(ApplianceCommandsApi.class) + path(method("commands"));
        String scheduled = path(ApplianceCommandsApi.class) + path(method("scheduled"));
        String byName = path(ApplianceCommandsApi.class) + path(method("cancelByAppliance"));
        String byId = path(ApplianceCommandsApi.class) + path(method("cancel"));
        assertEquals("/api/appliance-commands/{instanceId}/lists", list);
        assertEquals("/api/appliance-commands/{instanceId}/scheduled", scheduled);
        assertEquals(byName, scheduled);
        assertEquals("/api/appliance-commands/{instanceId}/scheduled/{id}", byId);

        for (String appliance : List.of("command-lists", "scheduled")) {
            String request = "/api/appliances/" + HOUSE + "/" + appliance;
            assertTrue(matches(legacy, request), request);
            assertFalse(matches(list, request), request);
            assertFalse(matches(scheduled, request), request);
            assertFalse(matches(byId, request), request);
        }
        String posted = "/api/appliance-commands/" + HOUSE + "/lists";
        assertTrue(matches(list, posted));
        assertFalse(matches(legacy, posted));
    }

    @Test
    void rolesRateLimitAndDeclaredStatuses() throws Exception {
        Method commands = method("commands");
        Method scheduled = method("scheduled");
        Method byName = method("cancelByAppliance");
        Method byId = method("cancel");
        for (Method method : List.of(commands, scheduled, byName, byId)) {
            assertEquals(Set.of("dashboard", "portal-admin"),
                    Set.of(method.getAnnotation(RolesAllowed.class).value()), method.getName());
        }
        assertEquals(RateLimitBucket.APPLIANCES_COMMANDS, commands.getAnnotation(RateLimited.class).value());
        assertNull(scheduled.getAnnotation(RateLimited.class));
        assertNull(byName.getAnnotation(RateLimited.class));
        assertNull(byId.getAnnotation(RateLimited.class));
        assertEquals(POST.class, commands.getAnnotation(POST.class).annotationType());
        assertNotNull(scheduled.getAnnotation(GET.class));
        assertNotNull(byName.getAnnotation(DELETE.class));
        assertNotNull(byId.getAnnotation(DELETE.class));
        assertEquals(Set.of("200", "400", "404", "409", "429"), codes(commands));
        assertEquals(Set.of("200"), codes(scheduled));
        assertEquals(Set.of("204", "400"), codes(byName));
        assertEquals(Set.of("204", "400"), codes(byId));
        Header cap = Arrays.stream(commands.getAnnotation(APIResponses.class).value())
                .filter(response -> "429".equals(response.responseCode()))
                .findFirst()
                .orElseThrow()
                .headers()[0];
        assertEquals(PendingCapHeader.NAME, cap.name());
        assertEquals(PendingCapHeader.FULL, cap.description());
        assertTrue(description(byName, "204").contains("X-CSRF-Token"));
        assertTrue(description(byId, "204").contains("freedriver-csrf"));
        assertEquals(List.class, commands.getReturnType());
        assertEquals(ApplianceCommandResult.class, listElement(commands));
        assertEquals(PendingApplianceCommand.class, listElement(scheduled));
        assertEquals(void.class, byName.getReturnType());
        assertEquals(void.class, byId.getReturnType());
    }

    @Test
    void listConflictsAreToldApartByBodyAndStaleIsCheckedFirst() {
        Method commands = method("commands");
        APIResponse conflict = response(commands, "409");
        assertEquals(1, conflict.content().length);
        Content body = conflict.content()[0];
        assertEquals(MediaType.APPLICATION_JSON, body.mediaType());
        assertEquals(InstanceView.class, body.schema().implementation());

        String described = conflict.description();
        assertTrue(described.startsWith("Only the stale-snapshot refusal carries the InstanceView body."), described);
        int stale = described.indexOf("Stale snapshot:");
        int neverScheduled = described.indexOf("Never scheduled:");
        int target = described.indexOf("target 404");
        assertTrue(stale >= 0, described);
        assertTrue(stale < neverScheduled, described);
        assertTrue(neverScheduled < target, described);
        String staleCase = described.substring(stale, neverScheduled);
        assertTrue(staleCase.contains("application/json"), staleCase);
        assertTrue(staleCase.contains("InstanceView with stale true"), staleCase);
        assertTrue(staleCase.contains("nothing is published"), staleCase);
        String neverScheduledCase = described.substring(neverScheduled, target);
        assertTrue(neverScheduledCase.contains("never sent a scheduled message"), neverScheduledCase);
        assertTrue(neverScheduledCase.contains("no entity"), neverScheduledCase);
        assertTrue(described.contains("The stale check runs before the never-scheduled check, "
                + "and both run before the target 404."), described);

        String notFound = description(commands, "404");
        int unknownHouse = notFound.indexOf("Unknown house:");
        int missingTarget = notFound.indexOf("Missing target:");
        assertTrue(unknownHouse >= 0, notFound);
        assertTrue(unknownHouse < missingTarget, notFound);
        assertTrue(notFound.substring(unknownHouse, missingTarget).contains("checked first"), notFound);
        assertTrue(notFound.substring(missingTarget).contains("checked last, after both 409 checks"), notFound);
        assertEquals(0, response(commands, "404").content().length);
    }

    @Test
    void legacyCommandAndScheduleStayAndAreDeprecated() throws Exception {
        Method command = AppliancesApi.class.getMethod(
                "command", UUID.class, String.class, ApplianceCommandRequest.class);
        Method schedule = McpOpsApi.class.getMethod("schedule", ScheduledSwitchRequest.class);
        assertNotNull(command.getAnnotation(Deprecated.class));
        assertNotNull(schedule.getAnnotation(Deprecated.class));
        assertEquals("/api/appliances", AppliancesApi.class.getAnnotation(Path.class).value());
        assertEquals("/{instanceId}/{applianceName}", command.getAnnotation(Path.class).value());
        assertEquals("/api/mcp", McpOpsApi.class.getAnnotation(Path.class).value());
        assertEquals("/scheduled-switches", schedule.getAnnotation(Path.class).value());
        assertEquals(Set.of("dashboard", "portal-admin"), Set.of(command.getAnnotation(RolesAllowed.class).value()));
        assertEquals(Set.of("dashboard", "portal-admin"), Set.of(schedule.getAnnotation(RolesAllowed.class).value()));
        assertEquals(RateLimitBucket.APPLIANCES_COMMANDS, command.getAnnotation(RateLimited.class).value());
        assertEquals(RateLimitBucket.APPLIANCES_SCHEDULES, schedule.getAnnotation(RateLimited.class).value());
        assertEquals(ApplianceCommandRequest.class, command.getParameterTypes()[2]);
        assertEquals(ScheduledSwitchRequest.class, schedule.getParameterTypes()[0]);
        assertEquals(Response.class, schedule.getReturnType());
    }

    @Test
    void blankApplianceNameAndOddIdFailBeforeACancel() throws Exception {
        ApplianceCommandsApi api = new ApplianceCommandsApi() {
            @Override
            public List<ApplianceCommandResult> commands(UUID instanceId, ApplianceCommandListRequest body) {
                return List.of();
            }

            @Override
            public List<PendingApplianceCommand> scheduled(UUID instanceId) {
                return List.of();
            }

            @Override
            public void cancelByAppliance(UUID instanceId, String applianceName) {
            }

            @Override
            public void cancel(UUID instanceId, String id) {
            }
        };
        Method byName = method("cancelByAppliance");
        Method byId = method("cancel");
        assertFalse(parameters(api, byName, HOUSE, null).isEmpty());
        assertFalse(parameters(api, byName, HOUSE, " ").isEmpty());
        assertFalse(parameters(api, byName, HOUSE, "n".repeat(ApplianceSchemas.NAME_MAX + 1)).isEmpty());
        assertTrue(parameters(api, byName, HOUSE, "hallway").isEmpty());
        assertTrue(parameters(api, byName, HOUSE, "n".repeat(ApplianceSchemas.NAME_MAX)).isEmpty());
        assertEquals(ApplianceSchemas.NAME_MAX, byName.getParameters()[1].getAnnotation(Size.class).max());
        assertNotNull(byName.getParameters()[1].getAnnotation(NotBlank.class));
        assertFalse(parameters(api, byId, HOUSE, "not-a-uuid").isEmpty());
        assertFalse(parameters(api, byId, HOUSE, "550E8400-E29B-41D4-A716-446655440000").isEmpty());
        assertTrue(parameters(api, byId, HOUSE, CANONICAL).isEmpty());
        assertNotNull(byId.getParameters()[1].getAnnotation(CanonicalUuid.class));
    }

    private static Class<?> listElement(Method method) {
        return (Class<?>) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
    }

    private static Method method(String name) {
        for (Method method : ApplianceCommandsApi.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static String path(Class<?> type) {
        return type.getAnnotation(Path.class).value();
    }

    private static String path(Method method) {
        Path path = method.getAnnotation(Path.class);
        return path == null ? "" : path.value();
    }

    private static boolean matches(String template, String path) {
        String[] expected = template.split("/");
        String[] actual = path.split("/");
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            String segment = expected[i];
            if (segment.startsWith("{") && segment.endsWith("}")) {
                if (actual[i].isEmpty()) {
                    return false;
                }
                continue;
            }
            if (!segment.equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> codes(Method method) {
        return Arrays.stream(method.getAnnotation(APIResponses.class).value())
                .map(APIResponse::responseCode)
                .collect(Collectors.toSet());
    }

    private static String description(Method method, String code) {
        return Arrays.stream(method.getAnnotation(APIResponses.class).value())
                .filter(response -> code.equals(response.responseCode()))
                .map(APIResponse::description)
                .findFirst()
                .orElse("");
    }

    private static APIResponse response(Method method, String code) {
        return Arrays.stream(method.getAnnotation(APIResponses.class).value())
                .filter(response -> code.equals(response.responseCode()))
                .findFirst()
                .orElseThrow();
    }

    private static <T> Set<ConstraintViolation<T>> parameters(T target, Method method, Object... args) {
        return VALIDATOR.forExecutables().validateParameters(target, method, args);
    }
}
