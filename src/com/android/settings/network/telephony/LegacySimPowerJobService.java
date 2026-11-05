/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.network.telephony;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class LegacySimPowerJobService extends JobService {
    private JobParameters mRunning;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        if (!LegacySimPowerController.isSupported(this)) return false;
        mRunning = parameters;
        LegacySimPowerController.get(this).reconcile(() -> {
            if (mRunning == parameters) {
                mRunning = null;
                jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        mRunning = null;
        return true;
    }
}
