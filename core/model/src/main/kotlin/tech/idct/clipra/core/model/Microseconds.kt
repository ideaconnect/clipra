package tech.idct.clipra.core.model

import kotlin.math.roundToLong

/** Times on the timeline are microseconds held in a [Long]. */
object Microseconds {
    const val PER_SECOND: Long = 1_000_000L

    fun fromSeconds(seconds: Double): Long = (seconds * PER_SECOND).roundToLong()

    fun toSeconds(us: Long): Double = us.toDouble() / PER_SECOND
}
