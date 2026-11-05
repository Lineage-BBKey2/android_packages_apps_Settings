/* SPDX-License-Identifier: GPL-3.0-only */
package com.github.iusmac.sevensim;

import android.util.Log;
import java.util.Locale;

/** Settings adapter for the upstream logging interface. */
public final class Logger {
    public interface Factory { Logger create(String tag); }
    private final String mTag;
    public Logger(String tag) { mTag = "Settings7SIM." + tag; }
    private void write(int level, String format, Object... args) {
        if (level >= Log.WARN || Log.isLoggable(mTag, level)) {
            Log.println(level, mTag, (args.length == 0 ? format : String.format(Locale.ROOT, format, args)));
        }
    }
    public void v(String f, Object... a) { write(Log.VERBOSE, f, a); }
    public void d(String f, Object... a) { write(Log.DEBUG, f, a); }
    public void w(String f, Object... a) { write(Log.WARN, f, a); }
    public void e(String f, Object... a) { write(Log.ERROR, f, a); }
}
