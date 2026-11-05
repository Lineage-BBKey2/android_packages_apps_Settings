/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.network.telephony;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserManager;
import android.telephony.SubscriptionManager;

import com.android.settings.R;

/** Confirmation and modem result reporting for the device-enabled legacy fallback. */
public final class LegacySimPowerToggleActivity extends Activity {
    private static final String EXTRA_ENABLED = "enabled";
    private ProgressDialog mProgress;

    public static Intent getIntent(Context context, int subId, boolean enabled) {
        return new Intent(context, LegacySimPowerToggleActivity.class)
                .putExtra("sub_id", subId).putExtra(EXTRA_ENABLED, enabled);
    }

    public static void launch(Context context, int subId, boolean enabled) {
        context.startActivity(getIntent(context, subId, enabled)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserManager users = getSystemService(UserManager.class);
        int subId = getIntent().getIntExtra("sub_id", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (!LegacySimPowerController.isSupported(this) || users == null || !users.isAdminUser()
                || users.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                || !SubscriptionManager.isValidSubscriptionId(subId)) {
            finish();
            return;
        }
        boolean enabled = getIntent().getBooleanExtra(EXTRA_ENABLED, true);
        if (enabled) {
            change(subId, true);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.legacy_sim_power_disable_title)
                    .setMessage(R.string.legacy_sim_power_disable_message)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> change(subId, false))
                    .setOnCancelListener(dialog -> finish())
                    .show();
        }
    }

    private void change(int subId, boolean enabled) {
        mProgress = new ProgressDialog(this);
        mProgress.setMessage(getString(R.string.legacy_sim_power_changing));
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);
        mProgress.show();
        LegacySimPowerController.get(this).setEnabled(subId, enabled, success -> {
            if (isFinishing() || isDestroyed()) return;
            mProgress.dismiss();
            if (success) {
                finish();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.legacy_sim_power_error_title)
                        .setMessage(R.string.legacy_sim_power_error_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                        .setOnCancelListener(dialog -> finish())
                        .show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mProgress != null) mProgress.dismiss();
        super.onDestroy();
    }
}
