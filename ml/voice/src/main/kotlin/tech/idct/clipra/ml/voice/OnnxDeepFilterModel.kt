package tech.idct.clipra.ml.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * DeepFilterNet 3's network on ONNX Runtime: the encoder, the ERB decoder and the deep filter
 * decoder from the official export (`enc.onnx`, `erb_dec.onnx`, `df_dec.onnx` in [folder]).
 */
class OnnxDeepFilterModel(folder: File, threads: Int = defaultThreads()) :
    DeepFilterModel,
    AutoCloseable {
    private val env = OnnxRuntime.environment
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val encoder = env.createSession(File(folder, "enc.onnx").path, options)
    private val erbDecoder = env.createSession(File(folder, "erb_dec.onnx").path, options)
    private val dfDecoder = env.createSession(File(folder, "df_dec.onnx").path, options)

    override fun run(erb: FloatArray, spec: FloatArray, frames: Int): DeepFilterModel.Estimate {
        val n = frames.toLong()
        val erbShape = longArrayOf(1, 1, n, DeepFilterNet.NB_ERB.toLong())
        val specShape = longArrayOf(1, 2, n, DeepFilterNet.NB_DF.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(erb), erbShape).use { featErb ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), specShape).use { featSpec ->
                encoder.run(mapOf("feat_erb" to featErb, "feat_spec" to featSpec)).use { enc ->
                    fun output(name: String) = enc.get(name).get() as OnnxTensor
                    val emb = output("emb")
                    val gains = erbDecoder.run(
                        mapOf(
                            "emb" to emb,
                            "e3" to output("e3"),
                            "e2" to output("e2"),
                            "e1" to output("e1"),
                            "e0" to output("e0"),
                        ),
                    ).use { floats(it.get(0) as OnnxTensor) }
                    val coefs = dfDecoder.run(
                        mapOf("emb" to emb, "c0" to output("c0")),
                        setOf("coefs"),
                    ).use { floats(it.get(0) as OnnxTensor) }
                    return DeepFilterModel.Estimate(gains, coefs)
                }
            }
        }
    }

    override fun close() {
        encoder.close()
        erbDecoder.close()
        dfDecoder.close()
        options.close()
    }

    private fun floats(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        /** The model's files, as the AI models manager installs them. */
        val FILES = listOf("enc.onnx", "erb_dec.onnx", "df_dec.onnx")

        private fun defaultThreads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
