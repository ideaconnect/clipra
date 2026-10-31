package tech.idct.clipra.ml.voice

import ai.onnxruntime.OrtEnvironment
import android.system.Os

/**
 * ONNX Runtime's environment, with its telemetry off: Clipra's runtimes send nothing (ADR
 * 0003). ONNX Runtime 1.31 carries Microsoft's 1DS telemetry; its start-up provider is removed
 * from the manifest, and this keeps it off should anything start it.
 */
internal object OnnxRuntime {
    val environment: OrtEnvironment by lazy {
        // Read by the native library when the environment is made.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    }
}
