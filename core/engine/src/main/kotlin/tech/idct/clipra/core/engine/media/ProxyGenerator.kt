package tech.idct.clipra.core.engine.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Makes the 720p H.264 proxy the editor plays: short side 720, a key frame every second for
 * quick seeking, sound kept. Export always reads the original.
 */
@OptIn(UnstableApi::class)
object ProxyGenerator {
    const val SHORT_SIDE = 720

    /** Videos larger than 720p get a proxy; smaller ones are edited as they are. */
    fun needsProxy(asset: MediaAsset): Boolean =
        asset.kind == MediaKind.Video && minOf(asset.width, asset.height) > SHORT_SIDE

    suspend fun generate(context: Context, source: Uri, output: File) {
        output.parentFile?.mkdirs()
        val partial = File(output.path + ".part").apply { delete() }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(SHORT_SIDE))))
            .build()
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        suspendCancellableCoroutine { continuation ->
            // Transformer runs on the thread that builds it; the main looper is always there.
            main.post {
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setiFrameIntervalSeconds(1f).build(),
                            )
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                    .also { it.start(item, partial.path) }
            }
            continuation.invokeOnCancellation { main.post { transformer?.cancel() } }
        }
        check(partial.renameTo(output)) { "Couldn't move the proxy into place" }
    }
}
