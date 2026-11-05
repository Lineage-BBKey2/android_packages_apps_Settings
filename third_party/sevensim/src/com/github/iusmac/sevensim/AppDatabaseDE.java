/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from 7SIM AppDatabaseDE: only the original subscriptions table is needed.
 */
package com.github.iusmac.sevensim;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverter;
import android.content.SharedPreferences;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.room.TypeConverters;
import com.github.iusmac.sevensim.telephony.Subscription;
import com.github.iusmac.sevensim.telephony.SubscriptionsDao;
import java.time.LocalDateTime;

@Database(entities = {Subscription.class}, version = 1, exportSchema = false)
@TypeConverters(AppDatabaseDE.Converters.class)
public abstract class AppDatabaseDE extends RoomDatabase {
    public abstract SubscriptionsDao subscriptionsDao();

    /** Repair orphaned v1 preferences without changing the SQLite schema/version. */
    public static Callback stateRepair(SharedPreferences preferences) {
        return new Callback() {
            @Override
            public void onOpen(SupportSQLiteDatabase db) {
                super.onOpen(db);
                String unknownTime = LocalDateTime.MIN.toString();
                db.execSQL("UPDATE subscriptions SET keepDisabledAcrossBoots = 0"
                        + " WHERE keepDisabledAcrossBoots = 1"
                        + " AND lastActivatedTime = ? AND lastDeactivatedTime = ?",
                        new Object[] {unknownTime, unknownTime});
                if (preferences.getInt("completed_version", 0) < 2
                        && !preferences.edit().putInt("completed_version", 2).commit()) {
                    throw new IllegalStateException("Could not record SIM state repair completion");
                }
            }
        };
    }

    /** Date conversion from the upstream converters, without scheduling/PIN dependencies. */
    public static final class Converters {
        @TypeConverter
        public static LocalDateTime readDate(String value) {
            return value == null ? null : LocalDateTime.parse(value);
        }
        @TypeConverter
        public static String writeDate(LocalDateTime value) {
            return value == null ? null : value.toString();
        }
    }
}
