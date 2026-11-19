package io.freedriver.sms.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsPhoneNumbersTest {

    private static final Set<String> DASHBOARD_RAWS = Set.of(
            "5555550123",
            "555-555-0123",
            "(555) 555-0123",
            "555.555.0123",
            "555 555 0123",
            "1-555-555-0123",
            "+1 555 555 0123",
            "+15555550123");

    private static final Set<String> ROUTER_RAWS = Set.of(
            "123-456-7890",
            "1234567890",
            "(123) 456-7890",
            "123.456.7890",
            "123 456 7890",
            "1-123-456-7890",
            "+1 123 456 7890",
            "+11234567890");

    private static final Set<String> NON_MATCHES = Set.of("55555501234", "call 5555550123");

    private record Example(String raw, String stored) {
    }

    private record Shapes(List<Example> dashboard, List<Example> router, List<String> nonMatches) {
    }

    @Test
    void sharedFixtureMapsEachShapeOnce() throws Exception {
        Shapes shapes = readShapes();
        assertOneGroup(shapes.dashboard(), DASHBOARD_RAWS);
        assertOneGroup(shapes.router(), ROUTER_RAWS);
        assertEquals(NON_MATCHES, Set.copyOf(shapes.nonMatches()));
        for (String raw : shapes.nonMatches()) {
            assertTrue(UsPhoneNumbers.canonical(raw).isEmpty(), raw);
        }

        List<Pattern> alternatives = shapeAlternatives();
        assertOneToOne(shapes.dashboard(), alternatives);
        assertOneToOne(shapes.router(), alternatives);

        Set<String> skeletons = new HashSet<>();
        for (Example example : shapes.dashboard()) {
            skeletons.add(skeleton(example.raw()));
        }
        for (Example example : shapes.router()) {
            skeletons.add(skeleton(example.raw()));
        }
        assertEquals(8, skeletons.size(), "separator skeletons");
        assertEquals(alternatives.size(), skeletons.size(), "separator skeletons");
    }

    @Test
    void nonMatchesNullAndACanonicalValue() {
        assertTrue(UsPhoneNumbers.canonical("55555501234").isEmpty());
        assertTrue(UsPhoneNumbers.canonical("call 5555550123").isEmpty());
        assertTrue(UsPhoneNumbers.canonical(null).isEmpty());
        assertFalse(UsPhoneNumbers.isCanonical(null));
        assertEquals("+15555550123", UsPhoneNumbers.canonical("+15555550123").orElseThrow());
        assertTrue(UsPhoneNumbers.isCanonical("+15555550123"));
        assertFalse(UsPhoneNumbers.isCanonical("+15555550123x"));
    }

    private static void assertOneGroup(List<Example> examples, Set<String> literals) {
        assertNotNull(examples);
        Set<String> raws = new HashSet<>();
        for (Example example : examples) {
            raws.add(example.raw());
            assertEquals(example.stored(), UsPhoneNumbers.canonical(example.raw()).orElseThrow(), example.raw());
            assertTrue(UsPhoneNumbers.isCanonical(example.stored()), example.raw());
        }
        assertEquals(literals, raws);
    }

    private static void assertOneToOne(List<Example> examples, List<Pattern> alternatives) {
        Set<Integer> matched = new HashSet<>();
        for (Example example : examples) {
            int hits = 0;
            int index = -1;
            for (int i = 0; i < alternatives.size(); i++) {
                if (alternatives.get(i).matcher(example.raw()).matches()) {
                    hits++;
                    index = i;
                }
            }
            assertEquals(1, hits, example.raw());
            assertTrue(matched.add(index), example.raw());
        }
        assertEquals(alternatives.size(), matched.size());
    }

    private static List<Pattern> shapeAlternatives() throws ReflectiveOperationException {
        Field field = UsPhoneNumbers.class.getDeclaredField("SHAPE");
        field.setAccessible(true);
        Pattern shape = (Pattern) field.get(null);
        List<Pattern> alternatives = new ArrayList<>();
        for (String alternative : topLevel(shape.pattern())) {
            alternatives.add(Pattern.compile(alternative));
        }
        return alternatives;
    }

    /** Splits on '|' that are not escaped. groupCount() is not an alternative count. */
    private static List<String> topLevel(String pattern) {
        List<String> alternatives = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '|') {
                alternatives.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        alternatives.add(current.toString());
        return alternatives;
    }

    private static String skeleton(String raw) {
        return raw.replaceAll("[0-9]", "");
    }

    private static Shapes readShapes() throws IOException {
        try (InputStream in = UsPhoneNumbersTest.class.getResourceAsStream("/us-phone-shapes.json")) {
            assertNotNull(in, "us-phone-shapes.json is not on the classpath");
            return new ObjectMapper().readValue(in, Shapes.class);
        }
    }
}
