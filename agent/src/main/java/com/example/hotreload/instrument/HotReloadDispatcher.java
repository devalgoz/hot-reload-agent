package com.example.hotreload.instrument;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime dispatch layer for instrumented bytecode.
 * Instrumented classes call these methods instead of accessing fields/methods directly.
 */
public class HotReloadDispatcher {

    private static final Map<String, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    public static Object getField(Object instance, String className, String fieldName) {
        try {
            Field f = resolveField(instance.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(instance);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get field " + fieldName + " on " + instance.getClass().getName(), e);
        }
    }

    public static void setField(Object instance, String className, String fieldName, Object value) {
        try {
            Field f = resolveField(instance.getClass(), fieldName);
            f.setAccessible(true);
            f.set(instance, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + fieldName + " on " + instance.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object instance, String className, String methodName,
                               String descriptor, Object... args) {
        try {
            Class<?>[] paramTypes = extractParamTypes(args);
            Method m = instance.getClass().getMethod(methodName, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(instance, args);
        } catch (Exception e) {
            throw new RuntimeException("Cannot invoke " + methodName + " on " + instance.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invokeStatic(Class<?> clazz, String methodName, String descriptor,
                                     Object... args) {
        try {
            Class<?>[] paramTypes = extractParamTypes(args);
            Method m = clazz.getMethod(methodName, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("Cannot invoke static " + methodName + " on " + clazz.getName(), e);
        }
    }

    private static Field resolveField(Class<?> clazz, String fieldName) {
        return FIELD_CACHE.computeIfAbsent(clazz.getName(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(fieldName, k -> {
                    try {
                        Field f = clazz.getDeclaredField(k);
                        f.setAccessible(true);
                        return f;
                    } catch (NoSuchFieldException e) {
                        for (Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
                            try {
                                Field f2 = c.getDeclaredField(k);
                                f2.setAccessible(true);
                                return f2;
                            } catch (NoSuchFieldException ex) { /* continue */ }
                        }
                        throw new RuntimeException("Field not found: " + k + " in " + clazz.getName());
                    }
                });
    }

    private static Class<?>[] extractParamTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }
}
