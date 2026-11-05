/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.network.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wake the bounded reconciliation job; do not run modem requests in a broadcast receiver. */
public final class LegacySimPowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LegacySimPowerController.scheduleReconcile(context);
    }
}
