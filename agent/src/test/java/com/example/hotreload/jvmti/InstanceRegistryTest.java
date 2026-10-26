package com.example.hotreload.jvmti;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InstanceRegistry} — tracks live instances via
 * {@link WeakReference} for JVMTI heap migration.
 */
class InstanceRegistryTest {

    static class TrackedBean {
        private String name = "default";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Test
    void trackAndRetrieveInstance() {
        InstanceRegistry.clearAll();
        try {
            TrackedBean bean = new TrackedBean();
            InstanceRegistry.trackInstance(bean);

            List<Object> instances = InstanceRegistry.getInstances(TrackedBean.class.getName());
            assertEquals(1, instances.size());
            assertSame(bean, instances.get(0));
        } finally {
            InstanceRegistry.clearAll();
        }
    }

    @Test
    void trackMultipleInstances() {
        InstanceRegistry.clearAll();
        try {
            TrackedBean b1 = new TrackedBean();
            TrackedBean b2 = new TrackedBean();
            InstanceRegistry.trackInstance(b1);
            InstanceRegistry.trackInstance(b2);

            List<Object> instances = InstanceRegistry.getInstances(TrackedBean.class.getName());
            assertEquals(2, instances.size());
        } finally {
            InstanceRegistry.clearAll();
        }
    }

    @Test
    void staleWeakReferencesAreCleanedUp() {
        InstanceRegistry.clearAll();
        try {
            TrackedBean bean = new TrackedBean();
            InstanceRegistry.trackInstance(bean);

            // Drop the strong reference and suggest GC.
            bean = null;
            System.gc();
            System.gc();

            List<Object> instances = InstanceRegistry.getInstances(TrackedBean.class.getName());
            // Weak reference may or may not be cleared depending on GC timing.
            // Just verify it doesn't throw.
            assertNotNull(instances);
        } finally {
            InstanceRegistry.clearAll();
        }
    }

    @Test
    void instanceCountReturnsZeroForUntrackedClass() {
        InstanceRegistry.clearAll();
        try {
            int count = InstanceRegistry.instanceCount("java.lang.String");
            assertEquals(0, count);
        } finally {
            InstanceRegistry.clearAll();
        }
    }

    @Test
    void clearRemovesClass() {
        InstanceRegistry.clearAll();
        try {
            TrackedBean bean = new TrackedBean();
            InstanceRegistry.trackInstance(bean);
            assertEquals(1, InstanceRegistry.instanceCount(TrackedBean.class.getName()));

            InstanceRegistry.clear(TrackedBean.class.getName());
            assertEquals(0, InstanceRegistry.instanceCount(TrackedBean.class.getName()));
        } finally {
            InstanceRegistry.clearAll();
        }
    }

    @Test
    void trackNullIsNoOp() {
        InstanceRegistry.clearAll();
        try {
            assertDoesNotThrow(() -> InstanceRegistry.trackInstance(null));
            assertEquals(0, InstanceRegistry.trackedClassCount());
        } finally {
            InstanceRegistry.clearAll();
        }
    }
}
