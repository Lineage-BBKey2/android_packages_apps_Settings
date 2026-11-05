/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.network.telephony

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Keep the last emitted rows during the brief subscription-list gap of a SIM power change.
 * A replacement update cancels the wait immediately; genuine removals are still delivered.
 * This is presentation only: callers must not use it to decide which SIM to power or enable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<List<T>>.keepLegacySimRowsWhileChanging(
    isChanging: () -> Boolean,
    subscriptionId: (T) -> Int,
    waitMillis: Long = 1_500L,
): Flow<List<T>> = flow {
    // Each collector owns its own previous list, including after lifecycle re-collection.
    var previousIds = emptySet<Int>()
    emitAll(this@keepLegacySimRowsWhileChanging.transformLatest { rows ->
        val ids = rows.map(subscriptionId).toSet()
        if (isChanging() && previousIds.any { it !in ids } && ids.all { it in previousIds }) {
            delay(waitMillis)
        }
        previousIds = ids
        emit(rows)
    })
}
