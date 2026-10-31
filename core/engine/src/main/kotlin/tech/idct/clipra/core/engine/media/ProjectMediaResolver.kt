package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import tech.idct.clipra.core.engine.render.CaptionRenderer
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.engine.render.TextRenderer
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.NoiseReduction
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.VoiceCleanup

/**
 * Finds a project's media for the engine. Preview reads the proxy when it is ready and the
 * original until then; export always reads the original. Missing media (moved or deleted, or
 * access lost) resolves to null, and its clips are left out instead of failing.
 */
class ProjectMediaResolver(
    private val context: Context,
    private val cache: MediaCache,
    private val forExport: Boolean,
    private val maxImageSide: Int,
) : MediaResolver {
    override fun source(asset: MediaAsset): String? {
        if (!forExport) {
            val proxy = cache.proxy(asset.id)
            if (proxy.exists()) return proxy.path
        }
        return asset.uri.takeIf { isAvailable(context, it) }
    }

    override fun cutout(asset: MediaAsset): CutoutMasks? =
        CutoutMasks.cached(cache.cutout(asset.id))

    override fun cameraMotion(asset: MediaAsset): CameraMotion? =
        Stabilizer.cached(cache.stabilize(asset.id))

    override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap =
        TextRenderer.render(context, spec, card, width, height)

    override fun animatedText(
        spec: TextSpec,
        card: Boolean,
        width: Int,
        height: Int,
        motion: Animations.TextMotion,
    ): Bitmap = TextRenderer.render(context, spec, card, width, height, motion)

    /**
     * What the clip plays: the noise-reduced sound once it is ready (#38), else the cleaned-up
     * mix (#39), else nothing, so the media's own sound plays.
     */
    override fun voice(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): String? {
        if (noise != null) {
            val gated = cache.voiceGated(asset.id, cleanup, NoiseReduction.percent(noise))
            if (gated.exists()) return gated.path
        }
        return cleanup?.let { cache.voiceMix(asset.id, it) }?.takeIf { it.exists() }?.path
    }

    override fun reversedSound(
        asset: MediaAsset,
        voice: String?,
        fromUs: Long,
        toUs: Long,
    ): String? = cache.reversedSound(ReversedSounds.key(asset, voice), fromUs, toUs)
        .takeIf { it.exists() }?.path

    override fun caption(caption: CaptionSpec, current: Int, width: Int, height: Int): Bitmap =
        CaptionRenderer.render(context, caption, current, width, height)

    override fun speech(asset: MediaAsset): List<LongRange>? =
        SoundAnalyzer.readSpeech(cache, asset.id)

    override fun lut(id: String): CubeLut? = Luts.builtIn(id) ?: LutLibrary.of(context).lut(id)

    override fun image(asset: MediaAsset): Bitmap? {
        if (!isAvailable(context, asset.uri)) return null
        return Thumbnails.decodePhoto(context, Uri.parse(asset.uri), maxImageSide)
    }

    companion object {
        /** Whether Clipra can still read [uri]. */
        fun isAvailable(context: Context, uri: String): Boolean = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
