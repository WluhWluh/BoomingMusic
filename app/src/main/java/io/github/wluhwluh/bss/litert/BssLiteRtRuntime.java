/*
 * Copyright 2026 Booming SS contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.wluhwluh.bss.litert;

/** Explicit-path bridge for the downloadable bounded OpenCL runtime. */
public final class BssLiteRtRuntime {
    public static final int CAPABILITY_SCHEMA_VERSION = 1;
    public static final String ARTIFACT_VERSION = "2.1.5-bss.2";
    public static final String GPU_PROFILE_ID = "gpu-opencl-bounded-fp32-v1";
    public static final int KERNEL_BATCH_SIZE = 1;
    public static final int COMMAND_QUEUE_WINDOW_SIZE = 1;

    private static boolean nativeLoaded;
    private static String loadedPath;

    private BssLiteRtRuntime() {}

    public static synchronized void loadAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("The bounded GPU library path is empty.");
        }
        if (nativeLoaded) {
            if (!path.equals(loadedPath)) {
                throw new IllegalStateException(
                        "The bounded GPU runtime is already loaded from another path.");
            }
            return;
        }
        System.load(path);
        loadedPath = path;
        nativeLoaded = true;
    }

    public static synchronized boolean isNativeLoaded() {
        return nativeLoaded;
    }

    public static Capability queryCapability() {
        if (!nativeLoaded) return Capability.unavailable();
        try {
            return new Capability(
                    true,
                    nativeGetCapabilitySchemaVersion(),
                    nativeGetArtifactVersion(),
                    nativeGetProfileId(),
                    nativeGetKernelBatchSize(),
                    nativeGetCommandQueueWindowSize());
        } catch (LinkageError | RuntimeException error) {
            return Capability.unavailable();
        }
    }

    public static void resetInferenceCounters() {
        requireNative();
        nativeResetInferenceCounters();
    }

    public static void beginInference() {
        requireNative();
        nativeSetInferenceEnabled(true);
    }

    public static void endInference() {
        requireNative();
        nativeSetInferenceEnabled(false);
    }

    public static long getDispatchCount() {
        requireNative();
        return nativeGetDispatchCount();
    }

    public static long getEventWaitCount() {
        requireNative();
        return nativeGetEventWaitCount();
    }

    private static void requireNative() {
        if (!nativeLoaded) {
            throw new IllegalStateException("The bounded OpenCL runtime is unavailable.");
        }
    }

    private static native int nativeGetCapabilitySchemaVersion();

    private static native String nativeGetArtifactVersion();

    private static native String nativeGetProfileId();

    private static native int nativeGetKernelBatchSize();

    private static native int nativeGetCommandQueueWindowSize();

    private static native void nativeResetInferenceCounters();

    private static native void nativeSetInferenceEnabled(boolean enabled);

    private static native long nativeGetDispatchCount();

    private static native long nativeGetEventWaitCount();

    public static final class Capability {
        private final boolean available;
        private final int schemaVersion;
        private final String artifactVersion;
        private final String profileId;
        private final int kernelBatchSize;
        private final int commandQueueWindowSize;

        private Capability(
                boolean available,
                int schemaVersion,
                String artifactVersion,
                String profileId,
                int kernelBatchSize,
                int commandQueueWindowSize) {
            this.available = available;
            this.schemaVersion = schemaVersion;
            this.artifactVersion = artifactVersion;
            this.profileId = profileId;
            this.kernelBatchSize = kernelBatchSize;
            this.commandQueueWindowSize = commandQueueWindowSize;
        }

        private static Capability unavailable() {
            return new Capability(false, 0, "", "", 0, 0);
        }

        public boolean isAvailable() { return available; }

        public int getSchemaVersion() { return schemaVersion; }

        public String getArtifactVersion() { return artifactVersion; }

        public String getProfileId() { return profileId; }

        public int getKernelBatchSize() { return kernelBatchSize; }

        public int getCommandQueueWindowSize() { return commandQueueWindowSize; }
    }
}
