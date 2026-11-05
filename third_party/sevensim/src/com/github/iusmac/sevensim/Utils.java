/* SPDX-License-Identifier: GPL-3.0-only */
package com.github.iusmac.sevensim;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Only the compatibility flags and toast helper used by the imported backend. */
public final class Utils {
    public static final boolean IS_AT_LEAST_R = Build.VERSION.SDK_INT >= 30;
    public static final boolean IS_AT_LEAST_S = Build.VERSION.SDK_INT >= 31;
    public static final boolean IS_OLDER_THAN_S = !IS_AT_LEAST_S;
    public static final boolean IS_AT_LEAST_V = Build.VERSION.SDK_INT >= 35;
    public static void makeToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
    private Utils() {}
}
