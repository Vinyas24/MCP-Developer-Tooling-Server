package com.mcpdev.tooling.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator is the single source of truth for both schema and binding, so these
 * tests pin its exact shapes — a change here is a client-visible contract change.
 */
class ToolSchemasTest {

    enum Mode {
        FAST, THOROUGH
    }

    record Nested(String label, Optional<Integer> depth) {
    }

    record Sample(String query, Integer limit, Boolean recursive, Mode mode,
                  List<String> tags, Optional<String> note, Nested nested) {
    }

    @Test
    @DisplayName("schema derives requiredness from Optional, nothing else")
    void requiredness() {
        Map<String, Object> schema = ToolSchemas.generate(Sample.class);
        var props = (Map<String, Object>) schema.get("properties");

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("query", "limit", "recursive", "mode", "tags", "nested"));
        assertThat(props.keySet()).containsExactlyInAnyOrder(
                "query", "limit", "recursive", "mode", "tags", "note", "nested");
    }

    @Test
    @DisplayName("type mapping is exact")
    void typeMapping() {
        Map<String, Object> props = (Map<String, Object>) ToolSchemas.generate(Sample.class).get("properties");

        assertThat(props.get("query")).isEqualTo(Map.of("type", "string"));
        assertThat(props.get("limit")).isEqualTo(Map.of("type", "integer"));
        assertThat(props.get("recursive")).isEqualTo(Map.of("type", "boolean"));
        assertThat(props.get("mode")).isEqualTo(Map.of("type", "string", "enum", List.of("FAST", "THOROUGH")));
        assertThat(props.get("tags")).isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
        assertThat(props.get("note")).isEqualTo(Map.of("type", "string")); // Optional unwraps
    }

    @Test
    @DisplayName("nested records become nested object schemas")
    void nestedRecords() throws Exception {
        Map<String, Object> props = (Map<String, Object>) ToolSchemas.generate(Sample.class).get("properties");

        assertThat(props.get("nested")).isEqualTo(Map.of(
                "type", "object",
                "properties", Map.of(
                        "label", Map.of("type", "string"),
                        "depth", Map.of("type", "integer")),
                "required", List.of("label")));
    }

    @Test
    @DisplayName("empty request record -> empty properties, empty required")
    void emptyRecord() {
        Map<String, Object> schema = ToolSchemas.generate(Empty.class);

        assertThat(schema.get("properties")).isEqualTo(Map.of());
        assertThat(schema.get("required")).isEqualTo(List.of());
    }

    record Empty() {
    }

    @Test
    @DisplayName("non-record and unsupported types fail fast at registration")
    void rejects() {
        assertThatThrownBy(() -> ToolSchemas.generate(String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static class NotARecord {
        public String field;
    }
}
