/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Derived from iusmac/7SIM, 15.0-dev, commit d5f1b6de4f8d229ec8678d6d7e18431e789f8cd9.
 * Settings adaptations: see third_party/sevensim/NOTICE and UPSTREAM-DIFF.patch.
 */
package com.github.iusmac.sevensim.telephony;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import androidx.annotation.ColorInt;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.github.iusmac.sevensim.AppDatabaseDE;
import com.github.iusmac.sevensim.Logger;
import com.github.iusmac.sevensim.SysProp;


import java.util.Iterator;
import java.util.Optional;


import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/**
 * {@inheritDoc}
 *
 * <p>The responsibility of this class is to provide to devices using the legacy Radio Interface
 * Layer (RIL) to disable/re-enable a subscription on a physical (non-eUICC) SIM, all
 * business-related information about available subscriptions found on the device using
 * {@link SubscriptionManager}.
 *
 * <p>The important peculiarity to mention of devices using the legacy RIL, is that powering down
 * the SIM card is equivalent to removing it, which means the SIM will completely disappear from the
 * system, even though it may be still present in the slot. In practice, this means we won't be able
 * to get the subscription data from the {@link SubscriptionManager} anymore. Fortunately, by design
 * these devices are tightly coupled with their available physical SIM slots, offering us a
 * straightforward workaround of always persisting each {@link Subscription}, and restoring as soon
 * as they appear unreachable, until the corresponding SIM slot becomes active again, i.e., visible
 * by the {@link SubscriptionManager}.
 *
 * <p>A device is considered to be using the legacy RIL when the response of
 * {@link TelephonyUtils#canDisableUiccSubscription} is {@code false}.
 */

public final class SubscriptionsImplLegacy extends Subscriptions {
    private final TelephonyUtils mTelephonyUtils;
    private final SysProp mSimSubIdSysProp;
    private final SysProp mSimStateSysProp;
    private final SysProp mSimIconTintSysProp;
    private final SysProp mSimNameSysProp;

    SubscriptionsImplLegacy(final Context context,
            final Logger.Factory loggerFactory, final AppDatabaseDE appDatabase,
            final SubscriptionManager subscriptionManager, final TelephonyUtils telephonyUtils,
            final SysProp simSubIdSysProp,
            final SysProp simStateSysProp,
            final SysProp simIconTintSysProp,
            final SysProp simNameSysProp,
            final SysProp subNameSysProp,
            final SysProp usableSimSubIdsSysProp) {

        super(context, loggerFactory, appDatabase, subscriptionManager, subNameSysProp,
                usableSimSubIdsSysProp);

        mTelephonyUtils = telephonyUtils;
        mSimSubIdSysProp = simSubIdSysProp;
        mSimStateSysProp = simStateSysProp;
        mSimIconTintSysProp = simIconTintSysProp;
        mSimNameSysProp = simNameSysProp;
    }

    /**
     * {@inheritDoc}
     *
     * <p>As per specs, the last snapshot of the subscriptions will be included if their
     * corresponding SIM slot was powered down and it's invisible by the
     * {@link SubscriptionManager}.
     */
    @Override
    @WorkerThread
    public Iterator<Subscription> iterator() {
        return new SubscriptionList(mSubscriptionManager) {
            /** The total number of active SIM slots configured by the vendor on this device. */
            private final int mActiveSlotCount = mTelephonyUtils.getActiveSlotCount();

            /**
             * {@inheritDoc}
             *
             * <p>Look up for the next enabled/disabled subscription in the active SIM slots.
             */
            @Override
            public boolean hasNext() {
                for (int i = mLastIndex; i < mActiveSlotCount; i++) {
                    if (mVisibleSubInfoList != null) {
                        for (SubscriptionInfo subInfo : mVisibleSubInfoList) {
                            if (subInfo.getSimSlotIndex() == i) {
                                mNextElementCandidate = createSubscription(subInfo);
                                mCurrentIndex = i;
                                return true;
                            }
                        }
                    }

                    // If there's no SubscriptionInfo for slot index, but SIM card appears to be
                    // disabled, then we restore the last subscription registered in the slot
                    if (hasDisabledSubscription(i)) {
                        mNextElementCandidate = restoreSubscription(i);
                        // A previous synchronization may have overwritten the volatile state.
                        // The durable keep-disabled preference still retains an enable path.
                        mNextElementCandidate.setSimState(SimState.DISABLED);
                        mCurrentIndex = i;
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * <p>Get subscription by slot index.
     *
     * <p>As per specs, the last snapshot of the subscriptions will be returned if their
     * corresponding SIM slot was powered down (will be invisible by the
     * {@link SubscriptionManager}).
     *
     * @param slotIndex The corresponding slot index.
     * @return An Optional containing the {@link Subscription}, if any.
     */
    @WorkerThread
    public Optional<Subscription> getSubscriptionForSimSlotIndex(final int slotIndex) {
        return getSubscription((sub) -> sub.getSlotIndex() == slotIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Subscription createSubscription(final SubscriptionInfo subInfo) {
        final Subscription sub = super.createSubscription(subInfo);

        sub.setSlotIndex(subInfo.getSimSlotIndex());
        // SubscriptionInfo can briefly remain selectable after the card reports ABSENT.
        // Do not overwrite an explicit disable with that stale active record: doing so would
        // erase the only volatile record used to reconstruct the off-slot entry afterwards.
        final boolean explicitDisable = Boolean.TRUE.equals(sub.getKeepDisabledAcrossBoots())
            || (getPersistedSubscriptionId(sub.getSlotIndex()) == sub.getId()
                && getPersistedSimState(sub.getSlotIndex()) == SimState.DISABLED);
        sub.setSimState(explicitDisable && mTelephonyUtils.isSimCardUnavailable(sub.getSlotIndex())
                ? SimState.DISABLED : SimState.ENABLED);

        return sub;
    }

    /** A real saved off state, not an arbitrary historical SIM. Runs on the serial worker. */
    private boolean hasDisabledSubscription(final int slotIndex) {
        if (getPersistedSimState(slotIndex) == SimState.DISABLED) return true;
        final int subId = getPersistedSubscriptionId(slotIndex);
        return subId != INVALID_SUBSCRIPTION_ID && mSubscriptionsDao.findBySubscriptionId(subId)
            .map((sub) -> Boolean.TRUE.equals(sub.getKeepDisabledAcrossBoots())).orElse(false);
    }

    /**
     * {@inheritDoc}
     *
     * As per specs, the last snapshot of the subscriptions must be returned if their corresponding
     * SIM slot was powered down (will be invisible by the {@link SubscriptionManager}), therefore,
     * we'll persist data that we can normally grab from the {@link SubscriptionManager} in volatile
     * memory.
     */
    @Override
    void persistSubscription(final Subscription sub) {
        final int slotIndex = sub.getSlotIndex();
        persistSimSubId(slotIndex, sub.getId());
        persistSimState(slotIndex, sub.getSimState());
        persistSimName(slotIndex, sub.getSimName());
        persistSimTintColor(slotIndex, sub.getIconTint());

        super.persistSubscription(sub);
    }

    /**
     * Restore the exact same {@link Subscription} instance previously persisted via
     * {@link #persistSubscription(Subscription)}.
     *
     * @param slotIndex The corresponding slot index.
     * @return An instance of {@link Subscription}.
     * @throws InvalidSubscriptionIdException When the persisted subscription ID is out of range.
     */
    @WorkerThread
    private Subscription restoreSubscription(final int slotIndex) {
        final int subId = getPersistedSubscriptionId(slotIndex);
        if (subId < 0) {
            // When restoring a subscription, we also expect to have a valid subscription ID,
            // otherwise we would pass around an invalid subscription instance
            throw new InvalidSubscriptionIdException(subId);
        }
        final Subscription subscription =
            mSubscriptionsDao.findBySubscriptionId(subId).orElseGet(() -> {
                final Subscription sub = new Subscription();
                sub.setId(subId);
                return sub;
            });

        subscription.setSlotIndex(slotIndex);
        subscription.setSimState(getPersistedSimState(slotIndex));
        subscription.setSimName(getPersistedSimName(slotIndex));
        subscription.setIconTint(getPersistedSimTintColor(slotIndex));

        return subscription;
    }

    /**
     * Get the SIM card associated subscription ID previously persisted in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @return The subscription ID or {@link INVALID_SUBSCRIPTION_ID}.
     */
    @IntRange(from = INVALID_SUBSCRIPTION_ID)
    private int getPersistedSubscriptionId(final int slotIndex) {
        return mSimSubIdSysProp.get(Optional.empty(), slotIndex).map((value) -> {
            try {
                final int subId = Integer.parseInt(value);
                if (subId > INVALID_SUBSCRIPTION_ID) {
                    return subId;
                }
            } catch (NumberFormatException e) { /* @SuppressWarnings("EmptyCatch") */ }

            mLogger.e("getPersistedSubscriptionId(slotIndex=%d) : Invalid SIM subscription ID: %s.",
                    slotIndex, value);

            return null;
        }).orElse(INVALID_SUBSCRIPTION_ID);
    }

    /**
     * Get the SIM card state previously persisted in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @return The SIM state or {@link Subscription#DEFAULT_SIM_STATE}.
     */
    @SimState
    private int getPersistedSimState(final int slotIndex) {
        return mSimStateSysProp.get(Optional.empty(), slotIndex).map((value) -> {
            try {
                final int state = Integer.parseInt(value);
                switch (state) {
                    case SimState.ENABLED, SimState.DISABLED -> {
                        return state;
                    }
                }
            } catch (NumberFormatException e) { /* @SuppressWarnings("EmptyCatch") */ }

            mLogger.e("getPersistedSimState(slotIndex=%d) : Invalid SIM state: %s.",
                    slotIndex, value);

            return null;
        }).orElse(Subscription.DEFAULT_SIM_STATE);
    }

    /**
     * Get SIM tint color previously persisted in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @return The packed tint color if any, otherwise {@link Subscription#DEFAULT_ICON_TINT}.
     */
    private @ColorInt int getPersistedSimTintColor(final int slotIndex) {
        return mSimIconTintSysProp.get(Optional.empty(), slotIndex).map((value) -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                mLogger.e("getPersistedSimTintColor(slotIndex=%d) : Invalid SIM tint: %s.",
                        slotIndex, value);
            }
            return null;
        }).orElse(Subscription.DEFAULT_ICON_TINT);
    }

    /**
     * Get SIM subscription name previously persisted in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @return The SIM subscription name if any, otherwise {@link Subscription#DEFAULT_SIM_NAME}.
     */
    private String getPersistedSimName(final int slotIndex) {
        return mSimNameSysProp.get(Optional.empty(), slotIndex)
            .orElse(Subscription.DEFAULT_SIM_NAME);
    }

    /**
     * Persist the SIM card associated subscription ID in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @param subId The subscription ID.
     */
    private void persistSimSubId(final int slotIndex, final int subId) {
        mSimSubIdSysProp.set(Optional.of(Integer.toString(subId)), slotIndex);
    }

    /**
     * Persist the SIM card state in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @param state One of {@link SimState}s.
     */
    private void persistSimState(final int slotIndex, final @SimState int state) {
        mSimStateSysProp.set(Optional.of(Integer.toString(state)), slotIndex);
    }

    /**
     * Persist the SIM card tint color in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @param tint The packed tint color integer.
     */
    private void persistSimTintColor(final int slotIndex, final @ColorInt int tint) {
        mSimIconTintSysProp.set(Optional.of(Integer.toString(tint)), slotIndex);
    }

    /**
     * Persist the SIM subscription name in volatile memory.
     *
     * @param slotIndex The corresponding SIM slot index.
     * @param subscriptionName The subscription name.
     */
    private void persistSimName(final int slotIndex, @Nullable String subscriptionName) {
        if ("".equals(subscriptionName)) {
            subscriptionName = null;
        }
        mSimNameSysProp.set(Optional.ofNullable(subscriptionName), slotIndex);
    }
}
