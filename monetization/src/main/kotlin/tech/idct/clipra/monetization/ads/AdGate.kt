package tech.idct.clipra.monetization.ads

/** The two export ads (docs/product/monetization.md, section 1). */
enum class AdSlot { Start, Finish }

/** Why a slot shows no ad. */
enum class AdSkip {
    /** Clipra Pro: no ads, not even a request. */
    Pro,

    /** Consent does not allow ad requests. */
    NoConsent,

    /** This slot already had its chance in this export. */
    AlreadyUsed,

    /** The user is not on the export progress screen with Clipra in front: never while editing. */
    NotWatching,

    /** The export failed or was cancelled. */
    NotSucceeded,

    /** Ad 1 closed less than 30 s before the export finished: never back to back. */
    TooSoon,

    Offline,

    /** No ad was ready within 2 s. */
    NotLoaded,
}

/** What the gate needs to know when a slot comes up. */
data class AdConditions(
    val pro: Boolean,
    val canRequestAds: Boolean,
    val online: Boolean,
    /** The export progress screen is on screen and Clipra is in front. */
    val watching: Boolean,
)

/**
 * Show or skip, for the two ads of one export (docs/product/monetization.md, section 1, rules 1 to
 * 5, 8 and 9). Times are elapsed milliseconds. The gate never delays the export: the render runs
 * whatever it answers.
 */
class AdGate {
    private var used = mutableSetOf<AdSlot>()
    private var startClosedAtMs: Long? = null
    private var finishedAtMs: Long? = null
    private var succeeded = false
    private var watchedEnd = false

    /** A new export started: each one may show Ad 1 and Ad 2 once. */
    fun exportStarted() {
        used = mutableSetOf()
        startClosedAtMs = null
        finishedAtMs = null
        succeeded = false
        watchedEnd = false
    }

    /**
     * The export finished ([succeeded]) or failed at [atMs]. Ad 2 is only for a user who was on the
     * progress screen then; the finish notification opens the saved video without an ad.
     */
    fun exportEnded(succeeded: Boolean, atMs: Long, watching: Boolean) {
        this.succeeded = succeeded
        finishedAtMs = atMs
        watchedEnd = watching
    }

    /** Null when [slot] may show an ad now, provided one loads within 2 s. */
    fun check(slot: AdSlot, conditions: AdConditions): AdSkip? = when {
        conditions.pro -> AdSkip.Pro
        !conditions.canRequestAds -> AdSkip.NoConsent
        slot in used -> AdSkip.AlreadyUsed
        !conditions.watching -> AdSkip.NotWatching
        slot == AdSlot.Finish && !succeeded -> AdSkip.NotSucceeded
        slot == AdSlot.Finish && !watchedEnd -> AdSkip.NotWatching
        slot == AdSlot.Finish && tooSoonAfterStart() -> AdSkip.TooSoon
        !conditions.online -> AdSkip.Offline
        else -> null
    }

    /** The slot had its chance, shown or skipped; it does not come back in this export. */
    fun used(slot: AdSlot) {
        used += slot
    }

    fun startClosed(atMs: Long) {
        startClosedAtMs = atMs
    }

    // Ad 1 still open when the export finished counts as closing after it.
    private fun tooSoonAfterStart(): Boolean {
        val closed = startClosedAtMs ?: return false
        val finished = finishedAtMs ?: return false
        return finished - closed < MIN_GAP_MS
    }

    companion object {
        /** AdMob: no interstitial right after another. */
        const val MIN_GAP_MS = 30_000L

        /** A slot whose ad is not ready by then is skipped. */
        const val LOAD_TIMEOUT_MS = 2_000L
    }
}
