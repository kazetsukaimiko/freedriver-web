package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dashboard.model.api.SignedInUser;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signed-in user record is subject and name. A blank name is omitted.
 */
class SignedInUserContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void componentsAreSubjectAndName() throws Exception {
        RecordComponent[] components = SignedInUser.class.getRecordComponents();
        assertEquals(List.of("subject", "name"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(String.class, components[0].getType());
        assertEquals(String.class, components[1].getType());
        assertNotNull(SignedInUser.class.getMethod("subject").getAnnotation(NotBlank.class));
        assertNull(SignedInUser.class.getMethod("name").getAnnotation(NotBlank.class));
        JsonInclude include = SignedInUser.class.getAnnotation(JsonInclude.class);
        assertEquals(JsonInclude.Include.NON_NULL, include.value());
    }

    @Test
    void blankSubjectFailsAndAPresentNamePasses() {
        assertFalse(VALIDATOR.validate(new SignedInUser(null, "Ada")).isEmpty());
        assertFalse(VALIDATOR.validate(new SignedInUser("", "Ada")).isEmpty());
        assertFalse(VALIDATOR.validate(new SignedInUser(" ", "Ada")).isEmpty());
        assertTrue(VALIDATOR.validate(new SignedInUser("oidc-sub-1", "Ada")).isEmpty());
        assertTrue(VALIDATOR.validate(new SignedInUser("oidc-sub-1", null)).isEmpty());
        assertTrue(VALIDATOR.validate(new SignedInUser("oidc-sub-1", " ")).isEmpty());
    }

    @Test
    void absentOrBlankNameSerializesWithNoNameKey() throws Exception {
        assertEquals("{\"subject\":\"oidc-sub-1\"}", json(new SignedInUser("oidc-sub-1", null)));
        assertEquals("{\"subject\":\"oidc-sub-1\"}", json(new SignedInUser("oidc-sub-1", "")));
        assertEquals("{\"subject\":\"oidc-sub-1\"}", json(new SignedInUser("oidc-sub-1", " ")));
        assertEquals("{\"subject\":\"oidc-sub-1\"}", json(new SignedInUser("oidc-sub-1", "\n")));
        assertEquals("{\"subject\":\"oidc-sub-1\",\"name\":\"Ada\"}",
                json(new SignedInUser("oidc-sub-1", "Ada")));
        SignedInUser blank = JSON.readValue("{\"subject\":\"oidc-sub-1\",\"name\":\" \"}", SignedInUser.class);
        assertNull(blank.name());
        assertEquals("{\"subject\":\"oidc-sub-1\"}", json(blank));
    }

    private static String json(SignedInUser user) throws JsonProcessingException {
        return JSON.writeValueAsString(user);
    }
}
