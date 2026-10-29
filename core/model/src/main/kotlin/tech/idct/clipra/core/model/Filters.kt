package tech.idct.clipra.core.model

/**
 * The 24 filters of spec section 6 (#25): 13 looks and 11 stylized, stored as an [Effect] whose
 * type is the filter's id. A clip has at most one filter; the effect stack (#29) adds more
 * effects next to it.
 */
object Filters {
    val looks = listOf(
        "vivid",
        "warm",
        "cool",
        "cinematic",
        "matte",
        "mono",
        "noir",
        "sepia",
        "vintage",
        "film",
        "night",
        "neon",
        "pastel",
    )

    val stylized = listOf(
        "mosaic",
        "blur",
        "glitch",
        "vhs",
        "grain",
        "vignette",
        "rgb-split",
        "light-leak",
        "halftone",
        "duotone",
        "soft-glow",
    )

    val all = looks + stylized

    /** Strength of a newly picked filter. */
    const val DEFAULT_STRENGTH = 0.8f

    /** Mosaic's block size, a fraction of the clip's shorter side (adjusted on the Masks screen). */
    const val MOSAIC_SIZE = "size"
    const val DEFAULT_MOSAIC_SIZE = 0.08f

    fun isFilter(type: String): Boolean = type in all
}

/** The clip's filter, if it has one. */
val Clip.filter: Effect? get() = effects.firstOrNull { Filters.isFilter(it.type) }
