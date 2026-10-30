package tech.idct.clipra.core.engine.render

import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Transform
import java.util.concurrent.ConcurrentHashMap

/**
 * Values that override a clip's own while a gesture is still going: a transform being dragged
 * (#19), an opacity being slid (#20), the effects while a filter's strength is slid or the
 * original is held for comparison (#25), or a mask being dragged (#27). The preview follows the finger without rebuilding the
 * composition. The UI thread writes them and the compositor's GL thread reads them.
 */
class LiveEdits {
    private val transforms = ConcurrentHashMap<String, Transform>()
    private val opacities = ConcurrentHashMap<String, Float>()
    private val effects = ConcurrentHashMap<String, List<Effect>>()
    private val masks = ConcurrentHashMap<String, Mask>()
    private val colors = ConcurrentHashMap<String, LiveColor>()
    private val splits = ConcurrentHashMap<String, Float>()
    private val cutouts = ConcurrentHashMap<String, Cutout>()

    /** The cutout's settings while the Cutout board's sliders move (#50). */
    fun cutout(clipId: String): Cutout? = cutouts[clipId]

    fun setCutout(clipId: String, cutout: Cutout?) {
        if (cutout == null) cutouts.remove(clipId) else cutouts[clipId] = cutout
    }

    /** A grade being changed (#28), with its LUT already read. */
    class LiveColor(val grade: ColorGrade, val lut: CubeLut?)

    fun color(clipId: String): LiveColor? = colors[clipId]

    fun setColor(clipId: String, color: LiveColor?) {
        if (color == null) colors.remove(clipId) else colors[clipId] = color
    }

    /** Where the Color board's before and after divide [clipId], across its picture. */
    fun split(clipId: String): Float? = splits[clipId]

    fun setSplit(clipId: String, split: Float?) {
        if (split == null) splits.remove(clipId) else splits[clipId] = split
    }

    fun transform(clipId: String): Transform? = transforms[clipId]

    fun opacity(clipId: String): Float? = opacities[clipId]

    fun setTransform(clipId: String, transform: Transform) {
        transforms[clipId] = transform
    }

    fun setOpacity(clipId: String, opacity: Float) {
        opacities[clipId] = opacity
    }

    fun effects(clipId: String): List<Effect>? = effects[clipId]

    fun mask(clipId: String): Mask? = masks[clipId]

    fun setMask(clipId: String, mask: Mask) {
        masks[clipId] = mask
    }

    /** Draws [clipId] with [list] instead of its effects; null goes back to its own. */
    fun setEffects(clipId: String, list: List<Effect>?) {
        if (list == null) effects.remove(clipId) else effects[clipId] = list
    }
}
