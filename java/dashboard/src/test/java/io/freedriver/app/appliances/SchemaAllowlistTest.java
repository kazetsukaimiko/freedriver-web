package io.freedriver.app.appliances;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SchemaAllowlistTest {

    @Test
    void restBody_rejectsCommandIdFromBrowser() {
        ApplianceCommandRequest request = new ApplianceCommandRequest();
        request.setOn(true);
        request.extra("commandId", "nope");
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<ApplianceCommandRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }
}
