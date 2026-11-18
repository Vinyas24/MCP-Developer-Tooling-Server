package com.mcpdev.tooling.registry;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * Binds a raw arguments map onto a tool's request record with a guaranteed
 * postcondition:
 * <ol>
 *   <li>every required component (type != Optional) present and non-null in the raw
 *       map, else {@link ToolInputException} listing all violations;</li>
 *   <li>every Optional component is a real Optional — Jackson 3 binds a MISSING
 *       Optional property to a null reference (verified empirically on 3.1.7), so the
 *       binder rebuilds the record substituting Optional.empty.</li>
 * </ol>
 * Tool code can therefore rely on {@code args.opt().filter(...)} without null-checks.
 * Required-missing is rejected here, not by a Jackson feature: explicit checks give
 * precise messages and don't depend on mapper config drift.
 */
final class ToolArgsBinder {

    private record Meta(Class<?> type, RecordComponent[] components, Constructor<?> ctor) {
    }

    private static final Map<Class<?>, Meta> META_CACHE = new ConcurrentHashMap<>();

    private final JsonMapper mapper;

    ToolArgsBinder(JsonMapper mapper) {
        this.mapper = mapper;
    }

    <P> P bind(Tool<P> tool, Map<String, Object> raw) {
        Meta meta = META_CACHE.computeIfAbsent(tool.requestType(), t -> {
            if (!t.isRecord()) {
                throw new IllegalStateException(
                        "Tool request type must be a record: " + t.getName());
            }
            RecordComponent[] components = t.getRecordComponents();
            Class<?>[] params = java.util.Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            try {
                return new Meta(t, components, t.getDeclaredConstructor(params));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No canonical constructor for " + t.getName(), e);
            }
        });

        List<String> missing = new ArrayList<>();
        for (RecordComponent component : meta.components()) {
            boolean required = component.getType() != Optional.class;
            String name = component.getName();
            Object value = raw == null ? null : raw.get(name);
            if (required && value == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolInputException(
                    "Missing required argument(s) for tool '" + tool.name() + "': "
                            + String.join(", ", missing));
        }

        P args = mapper.convertValue(raw, tool.requestType());
        return normalizeOptionals(meta, args, tool.name());
    }

    @SuppressWarnings("unchecked")
    private static <P> P normalizeOptionals(Meta meta, P args, String toolName) {
        Object[] values = new Object[meta.components().length];
        boolean dirty = false;
        try {
            for (int i = 0; i < meta.components().length; i++) {
                RecordComponent component = meta.components()[i];
                Object value = component.getAccessor().invoke(args);
                values[i] = value;
                if (component.getType() == Optional.class && value == null) {
                    values[i] = Optional.empty();
                    dirty = true;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot normalize arguments of tool '" + toolName + "'", e);
        }
        if (!dirty) {
            return args;
        }
        try {
            return (P) meta.ctor().newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot normalize arguments of tool '" + toolName + "'", e);
        }
    }
}
