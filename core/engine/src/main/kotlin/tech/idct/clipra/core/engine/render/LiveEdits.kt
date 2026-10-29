package tech.idct.clipra.core.engine.render

import tech.idct.clipra.core.model.Transform
import java.util.concurrent.ConcurrentHashMap

/**
 * Values that override a clip's own while a gesture is still going: a transform being dragged
 * (#19) or an opacity being slid (#20). The preview follows the finger without rebuilding the
 * composition. The UI thread writes them and the compositor's GL thread reads them.
 */
class LiveEdits {
    private val transforms = ConcurrentHashMap<String, Transform>()
    private val opacities = ConcurrentHashMap<String, Float>()

    fun transform(clipId: String): Transform? = transforms[clipId]

    fun opacity(clipId: String): Float? = opacities[clipId]

    fun setTransform(clipId: String, transform: Transform) {
        transforms[clipId] = transform
    }

    fun setOpacity(clipId: String, opacity: Float) {
        opacities[clipId] = opacity
    }
}
