package tech.idct.clipra.ml.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex FFT of any size made of the factors 2, 3, 4 and 5, such as DeepFilterNet's 960
 * (4 × 4 × 4 × 3 × 5): mixed-radix decimation in time, unnormalized in both directions.
 */
class Fft(val size: Int) {
    private val factors = factorize(size)
    private val twiddleRe = DoubleArray(size) { cos(-2 * PI * it / size) }
    private val twiddleIm = DoubleArray(size) { sin(-2 * PI * it / size) }
    private val scratchRe = DoubleArray(size)
    private val scratchIm = DoubleArray(size)
    private val sumRe = DoubleArray(MAX_RADIX)
    private val sumIm = DoubleArray(MAX_RADIX)
    private val termRe = DoubleArray(MAX_RADIX)
    private val termIm = DoubleArray(MAX_RADIX)

    /** Transforms [re] and [im] in place; [inverse] runs the unnormalized inverse. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        if (inverse) for (i in 0 until size) im[i] = -im[i]
        recurse(re, im, 0, 1, size, 0, 0)
        for (i in 0 until size) {
            re[i] = scratchRe[i]
            im[i] = if (inverse) -scratchIm[i] else scratchIm[i]
        }
    }

    /** Writes the DFT of `size / stride` input samples into the scratch at [outOffset]. */
    private fun recurse(
        re: DoubleArray,
        im: DoubleArray,
        inOffset: Int,
        stride: Int,
        n: Int,
        outOffset: Int,
        level: Int,
    ) {
        if (n == 1) {
            scratchRe[outOffset] = re[inOffset]
            scratchIm[outOffset] = im[inOffset]
            return
        }
        val p = factors[level]
        val m = n / p
        for (q in 0 until p) {
            recurse(re, im, inOffset + q * stride, stride * p, m, outOffset + q * m, level + 1)
        }
        val step = size / n
        val radixStep = size / p
        for (k in 0 until m) {
            // Twiddle the k-th output of each sub-transform, then a DFT of size p across them.
            for (q in 0 until p) {
                val index = outOffset + q * m + k
                val t = (q * k * step) % size
                val xr = scratchRe[index]
                val xi = scratchIm[index]
                termRe[q] = xr * twiddleRe[t] - xi * twiddleIm[t]
                termIm[q] = xr * twiddleIm[t] + xi * twiddleRe[t]
            }
            for (r in 0 until p) {
                var accRe = 0.0
                var accIm = 0.0
                for (q in 0 until p) {
                    val t = (q * r * radixStep) % size
                    accRe += termRe[q] * twiddleRe[t] - termIm[q] * twiddleIm[t]
                    accIm += termRe[q] * twiddleIm[t] + termIm[q] * twiddleRe[t]
                }
                sumRe[r] = accRe
                sumIm[r] = accIm
            }
            for (r in 0 until p) {
                scratchRe[outOffset + r * m + k] = sumRe[r]
                scratchIm[outOffset + r * m + k] = sumIm[r]
            }
        }
    }

    private companion object {
        const val MAX_RADIX = 5

        fun factorize(n: Int): IntArray {
            val factors = mutableListOf<Int>()
            var rest = n
            for (p in intArrayOf(4, 2, 3, 5)) {
                while (rest % p == 0) {
                    factors += p
                    rest /= p
                }
            }
            require(rest == 1) { "FFT size $n has factors other than 2, 3 and 5" }
            return factors.toIntArray()
        }
    }
}
