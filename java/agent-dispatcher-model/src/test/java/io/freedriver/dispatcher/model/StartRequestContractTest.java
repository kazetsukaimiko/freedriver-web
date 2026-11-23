package io.freedriver.dispatcher.model;

import static io.freedriver.dispatcher.model.ContractJson.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** The JSON and validation contract of {@link StartRequest} (#789). */
class StartRequestContractTest {

    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void a_body_with_only_deliveryRef_is_valid_and_reads_as_watch_mode() throws Exception {
        StartRequest request = JSON.readValue("{\"deliveryRef\":\"delivery-1\"}", StartRequest.class);

        assertEquals("delivery-1", request.deliveryRef());
        assertFalse(request.fireAndForget());
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void fireAndForget_true_round_trips() throws Exception {
        StartRequest request = JSON.readValue("{\"deliveryRef\":\"x\",\"fireAndForget\":true}", StartRequest.class);

        assertEquals(new StartRequest("x", true), request);
        assertTrue(VALIDATOR.validate(request).isEmpty());
        assertEquals(JSON.readTree("{\"deliveryRef\":\"x\",\"fireAndForget\":true}"), JSON.valueToTree(request));
        assertEquals(request, JSON.readValue(JSON.writeValueAsString(request), StartRequest.class));
    }

    @Test
    void a_watch_mode_start_serializes_as_deliveryRef_alone() throws Exception {
        StartRequest request = new StartRequest("x", false);

        assertEquals("{\"deliveryRef\":\"x\"}", JSON.writeValueAsString(request));
        assertEquals("{\"deliveryRef\":\"x\"}", JSON.writeValueAsString(new StartRequest("x")));
        assertEquals(request, JSON.readValue(JSON.writeValueAsString(request), StartRequest.class));
    }

    @Test
    void a_bare_json_string_is_rejected() {
        assertThrows(MismatchedInputException.class, () -> JSON.readValue("\"x\"", StartRequest.class));
    }

    @Test
    void the_one_argument_constructor_is_a_watch_mode_start() {
        assertEquals(new StartRequest("x", false), new StartRequest("x"));
    }

    @Test
    void deliveryRef_is_still_required() throws Exception {
        StartRequest request = JSON.readValue("{\"fireAndForget\":true}", StartRequest.class);

        assertEquals(1, VALIDATOR.validate(request).size());
    }
}
