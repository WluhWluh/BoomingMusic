package com.mardous.booming.separation.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LiteRtPackagingSmokeTest {
    @Test
    public void appPackagedCpuRuntimeRunsSmallModel() throws Exception {
        if ("x86".equals(Build.SUPPORTED_ABIS[0])) {
            assertFalse("The pure x86 process must be 32-bit", Process.is64Bit());
        }

        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File modelFile = new File(targetContext.getCacheDir(), "simple_add_dynamic_shape.tflite");
        try (InputStream input = testContext.getAssets().open(modelFile.getName());
                FileOutputStream output = new FileOutputStream(modelFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }

        Environment environment = Environment.create();
        CompiledModel model = null;
        List<TensorBuffer> inputBuffers = Collections.emptyList();
        List<TensorBuffer> outputBuffers = Collections.emptyList();
        try {
            assertTrue(environment.getAvailableAccelerators().contains(Accelerator.CPU));
            CompiledModel.Options options = new CompiledModel.Options(Accelerator.CPU);
            options.setCpuOptions(new CompiledModel.CpuOptions(4, null, null));
            model = CompiledModel.create(modelFile.getAbsolutePath(), options, environment);
            inputBuffers = model.createInputBuffers();
            outputBuffers = model.createOutputBuffers();
            assertEquals(2, inputBuffers.size());
            assertEquals(1, outputBuffers.size());

            float[] left = new float[512];
            float[] right = new float[512];
            Arrays.fill(left, 1.25f);
            Arrays.fill(right, -0.25f);
            inputBuffers.get(0).writeFloat(left);
            inputBuffers.get(1).writeFloat(right);
            model.run(inputBuffers, outputBuffers);

            float[] result = outputBuffers.get(0).readFloat();
            assertEquals(512, result.length);
            for (float value : result) {
                assertTrue(Float.isFinite(value));
                assertEquals(1.0f, value, 1e-5f);
            }
        } finally {
            for (TensorBuffer buffer : inputBuffers) {
                buffer.close();
            }
            for (TensorBuffer buffer : outputBuffers) {
                buffer.close();
            }
            if (model != null) {
                model.close();
            }
            environment.close();
            assertTrue(modelFile.delete() || !modelFile.exists());
        }
    }
}
