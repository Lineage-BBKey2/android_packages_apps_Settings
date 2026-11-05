/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.network.telephony

import android.content.Context
import android.provider.Settings
import com.android.settingslib.spaprivileged.settingsprovider.settingsGlobalBooleanFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

internal fun legacySimPowerChanges(context: Context): Flow<Unit> {
    if (!LegacySimPowerController.isSupported(context)) return flowOf(Unit)
    return callbackFlow {
        val controller = LegacySimPowerController.get(context)
        val listener = Runnable { trySend(Unit) }
        controller.addListener(listener)
        trySend(Unit)
        awaitClose { controller.removeListener(listener) }
    }
}

internal fun legacySimPowerChangeableFlow(context: Context): Flow<Boolean> {
    if (!LegacySimPowerController.isSupported(context)) return flowOf(true)
    return combine(
        legacySimPowerChanges(context),
        context.settingsGlobalBooleanFlow(Settings.Global.AIRPLANE_MODE_ON),
    ) { _, airplaneMode ->
        !airplaneMode && !LegacySimPowerController.get(context).isUiBusy
    }.distinctUntilChanged()
}
