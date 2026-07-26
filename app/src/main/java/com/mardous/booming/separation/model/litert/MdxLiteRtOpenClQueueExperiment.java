package com.mardous.booming.separation.model.litert;

import com.mardous.booming.BuildConfig;

/** Debug-only bridge for the OpenCL queue-window runtime experiment. */
final class MdxLiteRtOpenClQueueExperiment {
    private static final boolean ENABLED = initialize();

    private MdxLiteRtOpenClQueueExperiment() {}

    private static boolean initialize() {
        if (!BuildConfig.LITERT_OPENCL_QUEUE_EXPERIMENT ||
                !"aarch64".equals(System.getProperty("os.arch"))) {
            return false;
        }
        System.loadLibrary("OCLQ");
        return true;
    }

    static boolean isEnabled() {
        return ENABLED;
    }

    static void reset() {
        if (ENABLED) {
            nativeReset();
        }
    }

    static Integer kernelBatchSize() {
        if (!ENABLED) {
            return null;
        }
        int queueWindow = nativeGetQueueWindow();
        return queueWindow > 0 ? queueWindow : null;
    }

    static void beginInference() {
        if (ENABLED) {
            nativeSetInferenceEnabled(true);
        }
    }

    static void endInference() {
        if (ENABLED) {
            nativeSetInferenceEnabled(false);
        }
    }

    private static native void nativeReset();

    private static native int nativeGetQueueWindow();

    private static native void nativeSetInferenceEnabled(boolean enabled);
}
