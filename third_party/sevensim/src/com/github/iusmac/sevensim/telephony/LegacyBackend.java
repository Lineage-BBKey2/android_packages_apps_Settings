/* SPDX-License-Identifier: GPL-3.0-only */
package com.github.iusmac.sevensim.telephony;

import android.content.Context;
import android.media.AudioManager;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import androidx.room.Room;
import com.github.iusmac.sevensim.AppDatabaseDE;
import com.github.iusmac.sevensim.Logger;
import com.github.iusmac.sevensim.SysProp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Explicit construction adapter; replaces application-level Hilt/scheduler wiring only. */
public final class LegacyBackend {
    private final SubscriptionsImplLegacy mSubscriptions;
    private final TelephonyController mController;

    public LegacyBackend(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        AppDatabaseDE database = Room.databaseBuilder(storage, AppDatabaseDE.class,
                "settings_sevensim_subscriptions.db").addCallback(AppDatabaseDE.stateRepair(
                        storage.getSharedPreferences("settings_sevensim_state_repair", Context.MODE_PRIVATE))).build();
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        TelephonyUtils utils = new TelephonyUtils(telephony, subscriptions,
                () -> context.getSystemService(TelecomManager.class),
                () -> context.getSystemService(AudioManager.class), false);
        mSubscriptions = new SubscriptionsImplLegacy(context, Logger::new, database, subscriptions,
                utils, new SysProp("sim%d.sub_id", false), new SysProp("sim%d.state", false),
                new SysProp("sim%d.tint", false), new SysProp("sim%d.name", false),
                new SysProp("sub%d.state", false), new SysProp("usable_sub_ids", false));
        mController = new TelephonyController(context, Logger::new, telephony, mSubscriptions);
    }

    public void addListener(Subscriptions.OnSubscriptionsChangedListener listener) {
        mSubscriptions.addOnSubscriptionsChangedListener(listener);
    }

    /** All callers of the methods below run on the Settings adapter's serial worker. */
    public List<Subscription> snapshot() {
        List<Subscription> result = new ArrayList<>();
        for (Subscription sub : mSubscriptions) result.add(sub);
        return result;
    }

    public boolean setEnabled(int slot, boolean enabled, boolean keepDisabledAcrossBoots) {
        mController.setSimState(slot, enabled, keepDisabledAcrossBoots);
        return mController.wasLastRequestSuccessful();
    }

    public void sync() {
        mSubscriptions.syncSubscriptions(LocalDateTime.now());
    }

    /** Cancel a saved disable even if upstream found an already-enabled subscription. */
    public void clearDisabledIntent(Subscription sub) {
        sub.setSimState(SimState.ENABLED);
        sub.keepDisabledAcrossBoots(false);
        mSubscriptions.persistSubscription(sub);
    }

    /** Preserve an enable path if compensating power-up also failed; do not retry disable. */
    public void retainForRecovery(Subscription sub) {
        sub.setSimState(SimState.DISABLED);
        sub.keepDisabledAcrossBoots(false);
        mSubscriptions.persistSubscription(sub);
        mSubscriptions.notifyAllListeners();
    }
}
