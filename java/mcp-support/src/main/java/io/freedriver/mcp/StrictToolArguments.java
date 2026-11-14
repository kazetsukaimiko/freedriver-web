package io.freedriver.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * A tool argument takes its own JSON type, as the hand checks did before the MCP library:
 * 1.5 for an integer, 1 for an enum, "300" for an integer, "true" or 1 for a boolean, and 5 for a
 * string are invalid params, not converted values. The MCP library reads arguments with the
 * application's {@link ObjectMapper}, so this applies to every JSON body the service reads.
 */
@Singleton
public class StrictToolArguments implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}
