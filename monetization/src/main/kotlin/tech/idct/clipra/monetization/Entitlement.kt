package tech.idct.clipra.monetization

import kotlinx.coroutines.flow.Flow

/**
 * Whether the user owns Clipra Pro, from the cached entitlement (true offline too). Pro removes
 * the export ads and nothing else.
 */
interface Entitlement {
    val isPro: Flow<Boolean>
}
