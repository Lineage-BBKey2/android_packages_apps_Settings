/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Derived from iusmac/7SIM, 15.0-dev, commit d5f1b6de4f8d229ec8678d6d7e18431e789f8cd9.
 * Settings adaptations: see third_party/sevensim/NOTICE and UPSTREAM-DIFF.patch.
 */
package com.github.iusmac.sevensim.telephony;

class InvalidSubscriptionIdException extends RuntimeException {
    public InvalidSubscriptionIdException(final int invalidSubId) {
        super("Invalid subscription ID (must be >= 0): " + invalidSubId);
    }
}
