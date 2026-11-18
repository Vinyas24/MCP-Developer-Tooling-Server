package com.mcpdev.tooling.registry;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates the JSON input schema for a tool from its request record, so the schema
 * the client sees and the type the arguments are bound to cannot drift apart.
 *
 * <p>Type mapping (closed set — anything else fails fast at registration):</p>
 * <ul>
 *   <li>{@code String} -> string, {@code Integer}/{@code Long} -> integer,
 *       {@code Boolean} -> boolean</li>
 *   <li>enums -> string + enum values</li>
 *   <li>{@code List<T>} -> array with items of T</li>
 *   <li>{@code Optional<T>} -> schema of T, but NOT listed as required</li>
 *   <li>nested records -> nested object schema</li>
 * </ul>
 *
 * <p>Requiredness rule: every record component is required unless its type is
 * {@code Optional<...>}. No annotations — one explicit convention.</p>
 */
final class ToolSchemas {

    private static final String SCHEMA_DRAFT = "https://json-schema.org/draft/2020-12/schema";

    private ToolSchemas() {
    }

    static Map<String, Object> generate(Class<?> requestType) {
        if (!requestType.isRecord()) {
            throw new IllegalArgumentException(
                    "Tool request type must be a record: " + requestType.getName());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        var required = new LinkedHashSet<String>();

        for (RecordComponent component : requestType.getRecordComponents()) {
            boolean optional = isOptional(component);
            properties.put(component.getName(), schemaFor(component.getGenericType()));
            if (!optional) {
                required.add(component.getName());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", SCHEMA_DRAFT);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required.stream().toList());
        return schema;
    }

    private static boolean isOptional(RecordComponent component) {
        return component.getType() == Optional.class;
    }

    private static Map<String, Object> schemaFor(java.lang.reflect.Type type) {
        Class<?> raw = rawClass(type);

        if (raw == Optional.class) {
            // Nested/synthetic Optional resolution (e.g. inside Optional<Optional<T>> at
            // type-erasure level). Top-level Optional is already handled via requiredness.
            java.lang.reflect.Type inner = ((ParameterizedType) type).getActualTypeArguments()[0];
            return schemaFor(inner);
        }
        if (raw.isEnum()) {
            List<String> names = java.util.Arrays.stream(raw.getEnumConstants())
                    .map(Object::toString)
                    .toList();
            return Map.of("type", "string", "enum", names);
        }
        if (raw == String.class) {
            return Map.of("type", "string");
        }
        if (raw == Integer.class || raw == Long.class) {
            return Map.of("type", "integer");
        }
        if (raw == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (raw == Optional.class) {
            // List<Optional<T>> is rejected on purpose: ambiguous requiredness.
            throw new IllegalArgumentException(
                    "Optional inside a generic container is not supported");
        }
        if (raw == java.util.List.class) {
            java.lang.reflect.Type element = ((ParameterizedType) type).getActualTypeArguments()[0];
            return Map.of("type", "array", "items", schemaFor(element));
        }
        if (raw.isRecord()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("type", "object");
            nested.put("properties", generate(raw).get("properties"));
            nested.put("required", generate(raw).get("required"));
            return nested;
        }
        throw new IllegalArgumentException(
                "Unsupported tool argument type: " + raw.getName()
                        + " (add support in ToolSchemas or change the record)");
    }

    private static Class<?> rawClass(java.lang.reflect.Type type) {
        return type instanceof Class<?> c ? c : (Class<?>) ((ParameterizedType) type).getRawType();
    }
}
