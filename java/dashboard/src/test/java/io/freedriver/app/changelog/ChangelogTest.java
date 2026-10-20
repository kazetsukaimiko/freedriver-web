package io.freedriver.app.changelog;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogTest {

    @Test
    void reads_the_packaged_changelog() {
        Optional<String> text = new Changelog().text();
        assertTrue(text.isPresent());
        assertTrue(text.get().startsWith("# Changelog\n"), text.get());
    }

    @Test
    void absent_resource_is_empty() {
        assertEquals(Optional.empty(), Changelog.read("changelog/ABSENT.md"));
    }

    @Test
    void resource_is_outside_the_static_web_root() {
        assertEquals("changelog/CHANGELOG.md", Changelog.RESOURCE);
        assertFalse(Changelog.RESOURCE.startsWith("META-INF/resources"));
    }
}
