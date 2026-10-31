package tech.idct.clipra.feature.home

import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How long ago a project was edited, in the buckets Home shows ("Edited 2 h ago"). */
sealed interface EditedAgo {
    data object JustNow : EditedAgo

    data class Minutes(val count: Int) : EditedAgo

    data class Hours(val count: Int) : EditedAgo

    data object Yesterday : EditedAgo

    data class Days(val count: Int) : EditedAgo

    data object LastWeek : EditedAgo

    data class On(val date: LocalDate) : EditedAgo

    companion object {
        /**
         * Under a minute: just now. Under an hour: minutes. Earlier today: hours. The calendar
         * day before: yesterday. Then days up to a week, "last week" up to two, and the date.
         */
        fun of(
            nowMillis: Long,
            thenMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): EditedAgo {
            val minutes = (nowMillis - thenMillis).coerceAtLeast(0) / 60_000
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val day = Instant.ofEpochMilli(thenMillis).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(day, today).toInt()
            return when {
                minutes < 1 -> JustNow
                minutes < 60 -> Minutes(minutes.toInt())
                days <= 0 -> Hours((minutes / 60).toInt())
                days == 1 -> Yesterday
                days < 7 -> Days(days)
                days < 14 -> LastWeek
                else -> On(day)
            }
        }
    }
}

/** A duration as the design shows it, in Geist Mono: "0:42", "2:05", "1:02:03". */
fun formatDuration(us: Long): String {
    val totalSeconds = us / 1_000_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * The AI card's status (Main board): ready once a captions model is on the phone, naming the
 * downloaded models group by group, as "Whisper Base · DeepFilterNet 3"; null before that, when
 * the card says what the models are for. [whisper] names a captions model ("Whisper %1$s").
 */
fun aiStatus(installed: List<AiModel>, whisper: String, locale: Locale): AiStatus? {
    if (installed.none { it.group == ModelGroup.Captions }) return null
    val names = installed.sortedBy { it.group.ordinal }.map { model ->
        if (model.group == ModelGroup.Captions) whisper.format(model.name) else model.title(locale)
    }
    return AiStatus(ready = true, summary = names.joinToString(" · "))
}
