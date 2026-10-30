package tech.idct.clipra.core.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A 3D colour lookup table (#28): [size] points per side, red changing fastest, as a `.cube`
 * file lists them. [values] holds red, green and blue for each point. Colours are looked up
 * between [domainMin] and [domainMax] (0 to 1 unless the file says otherwise) and blended
 * between the eight nearest points.
 */
class CubeLut(
    val size: Int,
    val values: FloatArray,
    val title: String? = null,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "A LUT has $MIN_SIZE to $MAX_SIZE points a side" }
        require(values.size == size * size * size * 3) { "A LUT needs size³ colours" }
    }

    /** The colour at point ([r], [g], [b]), as an index into [values]. */
    fun index(r: Int, g: Int, b: Int): Int = ((b * size + g) * size + r) * 3

    /** [rgb] (0 to 1 each) looked up in the table. */
    fun sample(rgb: FloatArray): FloatArray {
        val n = size - 1
        val position = FloatArray(3) { channel ->
            val span = domainMax[channel] - domainMin[channel]
            ((rgb[channel] - domainMin[channel]) / span).coerceIn(0f, 1f) * n
        }
        val low = IntArray(3) { min(floor(position[it]).toInt(), n - 1).coerceAtLeast(0) }
        val t = FloatArray(3) { position[it] - low[it] }
        val out = FloatArray(3)
        for (corner in 0 until 8) {
            val dr = corner and 1
            val dg = (corner shr 1) and 1
            val db = (corner shr 2) and 1
            val weight = (if (dr == 1) t[0] else 1 - t[0]) *
                (if (dg == 1) t[1] else 1 - t[1]) *
                (if (db == 1) t[2] else 1 - t[2])
            if (weight == 0f) continue
            val i = index(low[0] + dr, low[1] + dg, low[2] + db)
            for (c in 0 until 3) out[c] += values[i + c] * weight
        }
        return out
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 129

        /** A table that changes nothing. */
        fun identity(size: Int = 33): CubeLut = of(size) { it }

        /** A table of [size] points a side filled from [map]. */
        fun of(size: Int, map: (FloatArray) -> FloatArray): CubeLut {
            val values = FloatArray(size * size * size * 3)
            val n = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val out = map(floatArrayOf(r / n, g / n, b / n))
                        for (c in 0 until 3) values[i++] = out[c].coerceIn(0f, 1f)
                    }
                }
            }
            return CubeLut(size, values)
        }

        /**
         * Reads a `.cube` file (Adobe's format): `LUT_3D_SIZE`, optional `TITLE` and
         * `DOMAIN_MIN`/`DOMAIN_MAX`, comments after `#`, then size³ lines of three numbers.
         * One-dimensional tables are refused, as is anything malformed.
         */
        fun parse(text: String): CubeLut {
            var size = 0
            var title: String? = null
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var count = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val first = line.first()
                if (first.isLetter()) {
                    val key = line.substringBefore(' ').uppercase()
                    val rest = line.substringAfter(' ', "").trim()
                    when (key) {
                        "TITLE" -> title = rest.trim('"')

                        "LUT_3D_SIZE" -> size = rest.toIntOrNull() ?: throw CubeError("size")

                        "LUT_1D_SIZE" -> throw CubeError("one-dimensional")

                        "DOMAIN_MIN" -> domainMin = triple(rest)

                        "DOMAIN_MAX" -> domainMax = triple(rest)

                        // LUT_3D_INPUT_RANGE and other keywords don't change the table.
                        else -> Unit
                    }
                    continue
                }
                if (size !in MIN_SIZE..MAX_SIZE) throw CubeError("size")
                val table = values ?: FloatArray(size * size * size * 3).also { values = it }
                if (count * 3 >= table.size) throw CubeError("too many points")
                val rgb = triple(line)
                for (c in 0 until 3) table[count * 3 + c] = rgb[c]
                count++
            }
            val table = values ?: throw CubeError("no points")
            if (count != size * size * size) throw CubeError("$count of ${size * size * size}")
            if ((0 until 3).any { domainMax[it] <= domainMin[it] }) throw CubeError("domain")
            return CubeLut(size, table, title, domainMin, domainMax)
        }

        private fun triple(text: String): FloatArray {
            val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size != 3) throw CubeError("not three numbers: $text")
            return FloatArray(3) { parts[it].toFloatOrNull() ?: throw CubeError(text) }
        }
    }
}

/** A `.cube` file that can't be used, and why. */
class CubeError(reason: String) : IllegalArgumentException(reason)

/**
 * The LUTs Clipra ships (#28), made from formulas here rather than files, and how LUTs are
 * named in a [ColorGrade]: `builtin:<name>` or `imported:<id>` for a `.cube` file the user
 * imported.
 */
object Luts {
    const val BUILT_IN = "builtin:"
    const val IMPORTED = "imported:"

    /** The built-in looks, in the order the LUT tab lists them. */
    val builtInNames: List<String> =
        listOf("teal_orange", "warm_film", "cool_night", "silver_mono", "faded")

    fun builtInId(name: String) = BUILT_IN + name

    /** The built-in LUT with [id], or null when [id] isn't one. */
    @Synchronized
    fun builtIn(id: String): CubeLut? {
        val name = id.removePrefix(BUILT_IN).takeIf { id.startsWith(BUILT_IN) } ?: return null
        if (name !in builtInNames) return null
        return cache.getOrPut(name) { CubeLut.of(SIZE) { look(name, it) } }
    }

    private val cache = HashMap<String, CubeLut>()

    private const val SIZE = 33

    private fun look(name: String, c: FloatArray): FloatArray {
        val luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        return when (name) {
            // Shadows toward teal, light toward orange.
            "teal_orange" -> {
                val shadow = 1 - smooth(0.15f, 0.6f, luma)
                val light = smooth(0.35f, 0.9f, luma)
                floatArrayOf(
                    c[0] - 0.08f * shadow + 0.08f * light,
                    c[1] + 0.02f * shadow + 0.02f * light,
                    c[2] + 0.1f * shadow - 0.08f * light,
                )
            }

            // A soft S-curve, lifted blacks and a warm cast.
            "warm_film" -> curve(c, 0.06f, 0.96f, 0.25f).let {
                floatArrayOf(it[0] * 1.05f + 0.01f, it[1] * 1.0f, it[2] * 0.9f)
            }

            // Darker and bluer, with cyan shadows.
            "cool_night" -> floatArrayOf(c[0] * 0.82f, c[1] * 0.9f + 0.02f, c[2] * 1.05f + 0.05f)

            // Black and white with a little contrast.
            "silver_mono" -> curve(floatArrayOf(luma, luma, luma), 0.02f, 0.98f, 0.35f)

            // Washed out: lifted blacks, softer whites, less colour.
            "faded" -> FloatArray(3) { 0.1f + 0.82f * (luma + (c[it] - luma) * 0.7f) }

            else -> c
        }
    }

    /** From [black] to [white] along an S-curve of [strength]. */
    private fun curve(c: FloatArray, black: Float, white: Float, strength: Float) = FloatArray(3) {
        val x = c[it]
        val s = x * x * (3 - 2 * x)
        black + (white - black) * (x + (s - x) * strength)
    }

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Fixed points of a ramp from dark to light, for a swatch of the look. */
    fun swatch(lut: CubeLut, steps: Int = 2): List<FloatArray> = (0 until steps).map { i ->
        val v = 0.15f + 0.7f * i / max(1, steps - 1)
        lut.sample(floatArrayOf(v * 1.05f, v, v * 0.9f))
    }
}
