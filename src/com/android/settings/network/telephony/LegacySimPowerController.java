/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.network.telephony;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.settings.R;
import com.github.iusmac.sevensim.telephony.LegacyBackend;
import com.github.iusmac.sevensim.telephony.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Settings UI/lifecycle adapter. SIM transactions and subscription state are owned by 7SIM. */
public final class LegacySimPowerController {
    private static final String TAG = "LegacySimPower";
    private static final int JOB_ID = 0x4b325349;
    private static LegacySimPowerController sInstance;

    public interface Callback { void onComplete(boolean success); }

    private final Context mContext;
    private final TelephonyManager mTelephony;
    private final SubscriptionManager mSubscriptions;
    private final LegacyBackend mBackend;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mBusy = new AtomicBoolean(true);
    // Reconciliation also uses mBusy for serialization, even when no power change is needed.
    // Do not make those read-only checks pulse the UI's changeable state.
    private volatile boolean mUiBusy = true;
    private volatile long mUiSettlingUntil;
    private final CopyOnWriteArraySet<Runnable> mListeners = new CopyOnWriteArraySet<>();
    private volatile List<Subscription> mSnapshot = Collections.emptyList();

    private LegacySimPowerController(Context context) {
        mContext = context.getApplicationContext();
        mTelephony = mContext.getSystemService(TelephonyManager.class);
        mSubscriptions = mContext.getSystemService(SubscriptionManager.class);
        mBackend = new LegacyBackend(mContext);
        mBackend.addListener(() -> mWorker.execute(() -> {
            try { refresh(true); }
            catch (RuntimeException e) { Log.e(TAG, "Could not refresh 7SIM state", e); }
        }));
        mWorker.execute(() -> {
            try { refresh(true); }
            catch (RuntimeException e) { Log.e(TAG, "Could not initialize 7SIM state", e); }
            finally { mBusy.set(false); mUiBusy = false; notifyChanged(); }
        });
    }

    public static synchronized LegacySimPowerController get(Context context) {
        if (sInstance == null) sInstance = new LegacySimPowerController(context);
        return sInstance;
    }

    public static boolean isConfigured(Context context) {
        return context.getResources().getBoolean(R.bool.config_enable_legacy_sim_power_toggle);
    }

    public static boolean isSupported(Context context) {
        if (!isConfigured(context)) return false;
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        return subscriptions != null && !subscriptions.canDisablePhysicalSubscription();
    }

    public static boolean isDisabled(Context context, int subId) {
        if (!isSupported(context)) return false;
        Subscription sub = get(context).find(subId);
        return sub != null && !sub.isSimEnabled();
    }

    public static boolean canHandle(Context context, int subId) {
        if (!isSupported(context)) return false;
        LegacySimPowerController controller = get(context);
        if (controller.find(subId) != null) return true;
        SubscriptionInfo info = controller.mSubscriptions.getActiveSubscriptionInfo(subId);
        return info != null && !info.isEmbedded();
    }

    public static String getDisabledSummary(Context context, int subId) {
        if (!isSupported(context)) return null;
        Subscription sub = get(context).find(subId);
        if (sub == null || sub.isSimEnabled()) return null;
        return context.getString(Boolean.TRUE.equals(sub.getKeepDisabledAcrossBoots())
                ? R.string.legacy_sim_power_slot_off : R.string.legacy_sim_power_slot_recovery,
                sub.getSlotIndex() + 1);
    }

    public boolean isBusy() { return mBusy.get(); }
    public boolean isUiBusy() { return mUiBusy; }
    public boolean isInUiTransition() {
        return mUiBusy || SystemClock.elapsedRealtime() < mUiSettlingUntil;
    }

    private void beginUiChange() {
        mUiBusy = true;
        notifyChanged();
    }

    private void endUiChange() {
        if (mUiBusy) mUiSettlingUntil = SystemClock.elapsedRealtime() + 5_000;
        mUiBusy = false;
    }
    public void addListener(Runnable listener) { mListeners.add(listener); }
    public void removeListener(Runnable listener) { mListeners.remove(listener); }

    private void notifyChanged() {
        mMain.post(() -> { for (Runnable listener : mListeners) listener.run(); });
    }

    private Subscription find(int subId) {
        for (Subscription sub : mSnapshot) if (sub.getId() == subId) return sub;
        return null;
    }

    /** Room and upstream subscription iteration stay on this adapter's serial worker. */
    private void refresh(boolean sync) {
        if (sync) mBackend.sync();
        mSnapshot = Collections.unmodifiableList(new ArrayList<>(mBackend.snapshot()));
        notifyChanged();
    }

    /** Reuse real historical SubscriptionInfo only for entries retained by the 7SIM backend. */
    public static List<SubscriptionInfo> includeDisabled(Context context,
            List<SubscriptionInfo> available) {
        if (!isSupported(context)) return available;
        LegacySimPowerController controller = get(context);
        List<SubscriptionInfo> result = new ArrayList<>(available);
        List<SubscriptionInfo> all = controller.mSubscriptions.getAllSubscriptionInfoList();
        if (all == null) return result;
        int modemCount = controller.mTelephony.getActiveModemCount();
        for (Subscription sub : controller.mSnapshot) {
            if (sub.isSimEnabled() || sub.getSlotIndex() < 0 || sub.getSlotIndex() >= modemCount
                    || result.stream().anyMatch(info -> info.getSubscriptionId() == sub.getId())) {
                continue;
            }
            SubscriptionInfo inserted = controller.mSubscriptions
                    .getActiveSubscriptionInfoForSimSlotIndex(sub.getSlotIndex());
            if (inserted != null && inserted.getSubscriptionId() != sub.getId()) continue;
            for (SubscriptionInfo info : all) {
                if (info.getSubscriptionId() == sub.getId() && !info.isEmbedded()
                        && !info.isOpportunistic()) {
                    result.add(info);
                    break;
                }
            }
        }
        return result;
    }

    private boolean radioChangesBlocked() {
        if (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0) return true;
        TelecomManager telecom = mContext.getSystemService(TelecomManager.class);
        if (telecom != null && telecom.isInCall()) return true;
        List<SubscriptionInfo> active = mSubscriptions.getActiveSubscriptionInfoList();
        if (active != null) {
            for (SubscriptionInfo info : active) {
                if (mTelephony.createForSubscriptionId(info.getSubscriptionId())
                        .getEmergencyCallbackMode()) return true;
            }
        }
        return false;
    }

    private static boolean ready(int state) {
        return state == TelephonyManager.SIM_STATE_READY || state == TelephonyManager.SIM_STATE_LOADED;
    }

    private boolean change(Subscription sub, boolean enabled) throws InterruptedException {
        beginUiChange();
        boolean accepted = mBackend.setEnabled(sub.getSlotIndex(), enabled, !enabled);
        if (accepted && enabled) {
            mBackend.clearDisabledIntent(sub);
        } else if (accepted) {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (ready(mTelephony.getSimState(sub.getSlotIndex()))
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(250);
            if (ready(mTelephony.getSimState(sub.getSlotIndex()))) accepted = false;
        }
        if (!accepted && !enabled) {
            // Upstream issues compensating power-up on failure. Give that request time to settle.
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (!ready(mTelephony.getSimState(sub.getSlotIndex()))
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
            if (ready(mTelephony.getSimState(sub.getSlotIndex()))) {
                mBackend.clearDisabledIntent(sub);
            } else {
                mBackend.retainForRecovery(sub);
            }
        }
        return accepted;
    }

    public void setEnabled(int subId, boolean enabled, Callback callback) {
        UserManager users = mContext.getSystemService(UserManager.class);
        if (!isSupported(mContext) || users == null || !users.isAdminUser()
                || users.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                || !mBusy.compareAndSet(false, true)) {
            mMain.post(() -> callback.onComplete(false));
            return;
        }
        mUiBusy = true;
        notifyChanged();
        mWorker.execute(() -> {
            boolean success = false;
            Subscription requested = null;
            try {
                if (radioChangesBlocked()) throw new IllegalStateException("Radio change blocked");
                refresh(false);
                Subscription sub = find(subId);
                if (sub == null || sub.getSlotIndex() < 0
                        || sub.getSlotIndex() >= mTelephony.getActiveModemCount()) {
                    throw new IllegalStateException("Physical SIM is not available");
                }
                if (!enabled && !ready(mTelephony.getSimState(sub.getSlotIndex()))) {
                    throw new IllegalStateException("Unlock the SIM before disabling it");
                }
                requested = sub;
                success = change(sub, enabled);
            } catch (Exception e) {
                Log.e(TAG, "Could not change SIM state", e);
                if (requested != null && !enabled) {
                    try {
                        if (ready(mTelephony.getSimState(requested.getSlotIndex()))) {
                            mBackend.clearDisabledIntent(requested);
                        } else {
                            mBackend.retainForRecovery(requested);
                        }
                    } catch (RuntimeException recoveryError) {
                        Log.e(TAG, "Could not save recovery state", recoveryError);
                    }
                }
            } finally {
                try { refresh(false); }
                catch (RuntimeException e) { Log.e(TAG, "Could not refresh 7SIM state", e); }
                endUiChange();
                mBusy.set(false);
                notifyChanged();
            }
            final boolean result = success;
            mMain.post(() -> callback.onComplete(result));
        });
    }

    public static void scheduleReconcile(Context context) {
        if (!isConfigured(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, LegacySimPowerJobService.class))
                    .setMinimumLatency(1000).setOverrideDeadline(10000).build());
        }
    }

    public void reconcile(Runnable finished) {
        mWorker.execute(() -> {
            boolean acquired = false;
            try {
                if (!isSupported(mContext) || !mBusy.compareAndSet(false, true)) return;
                acquired = true;
                if (radioChangesBlocked()) return;
                refresh(false);
                for (Subscription sub : mSnapshot) {
                    if (sub.isSimEnabled() && Boolean.TRUE.equals(sub.getKeepDisabledAcrossBoots())
                            && sub.getSlotIndex() >= 0
                            && sub.getSlotIndex() < mTelephony.getActiveModemCount()
                            && ready(mTelephony.getSimState(sub.getSlotIndex()))) {
                        if (radioChangesBlocked()) break;
                        change(sub, false);
                    }
                }
                refresh(true);
            } catch (Exception e) {
                Log.e(TAG, "Could not reconcile 7SIM state", e);
            } finally {
                if (acquired) { endUiChange(); mBusy.set(false); notifyChanged(); }
                mMain.post(finished);
            }
        });
    }
}
