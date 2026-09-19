package com.aclaniakea.colorosporttuning;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ContractProbe & CircuitBreaker Seam
 * 
 * Provides runtime pre-flight verification of target OEM app classes and method signatures,
 * tracks failure health per feature, and activates fallback strategies when protocol drift
 * or continuous exceptions are detected.
 */
public final class ContractProbe {

    public enum Status {
        HEALTHY,
        DRIFTED,
        INCOMPATIBLE
    }

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final ConcurrentHashMap<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> TRIPPED_CIRCUITS = new ConcurrentHashMap<>();

    private ContractProbe() { }

    /** Verify that a target class exists in the provided ClassLoader. */
    public static boolean verifyClass(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable th) {
            HookUtils.log("ContractProbe: class missing: " + className + " (" + th.getMessage() + ")");
            return false;
        }
    }

    /** Verify that a target method exists with the expected parameter types. */
    public static boolean verifyMethod(ClassLoader loader, String className,
            String methodName, Class<?>... paramTypes) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            Method m = clazz.getDeclaredMethod(methodName, paramTypes);
            return m != null;
        } catch (Throwable th) {
            HookUtils.log("ContractProbe: method missing: " + className + "#"
                    + methodName + " (" + th.getMessage() + ")");
            return false;
        }
    }

    /** Check if a circuit breaker has been tripped for the specified feature. */
    public static boolean isTripped(String featureKey) {
        return Boolean.TRUE.equals(TRIPPED_CIRCUITS.get(featureKey));
    }

    /** Reset the circuit breaker for a feature on recovery. */
    public static void reset(String featureKey) {
        FAILURES.remove(featureKey);
        TRIPPED_CIRCUITS.remove(featureKey);
    }

    /** Record a successful execution to reset the failure counter. */
    public static void recordSuccess(String featureKey) {
        AtomicInteger count = FAILURES.get(featureKey);
        if (count != null) {
            count.set(0);
        }
    }

    /** Record a failure; if threshold reached, trip the circuit breaker. */
    public static void recordFailure(String featureKey, Throwable cause) {
        AtomicInteger count = FAILURES.computeIfAbsent(featureKey, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        HookUtils.log("ContractProbe: feature [" + featureKey + "] failure count=" + current
                + " error=" + (cause != null ? cause.getMessage() : "null"));
        if (current >= MAX_CONSECUTIVE_FAILURES) {
            TRIPPED_CIRCUITS.put(featureKey, true);
            HookUtils.log("ContractProbe: !!! CIRCUIT BREAKER TRIPPED for [" + featureKey
                    + "] -> activating fallback mode to protect host app");
        }
    }

    public interface FallbackAction<T> {
        T execute() throws Throwable;
    }

    public interface PrimaryAction<T> {
        T execute() throws Throwable;
    }

    /** Execute primary action guarded by the circuit breaker; fall back if tripped or throws. */
    public static <T> T executeGuarded(String featureKey, PrimaryAction<T> primary,
            FallbackAction<T> fallback) {
        if (isTripped(featureKey)) {
            HookUtils.log("ContractProbe: executing fallback for tripped feature: " + featureKey);
            try {
                return fallback != null ? fallback.execute() : null;
            } catch (Throwable th) {
                HookUtils.log("ContractProbe: fallback execution failed for " + featureKey + ": " + th);
                return null;
            }
        }
        try {
            T result = primary.execute();
            recordSuccess(featureKey);
            return result;
        } catch (Throwable th) {
            recordFailure(featureKey, th);
            if (fallback != null) {
                try {
                    return fallback.execute();
                } catch (Throwable fbErr) {
                    HookUtils.log("ContractProbe: fallback also failed: " + fbErr);
                }
            }
            return null;
        }
    }
}
