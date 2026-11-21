package io.github.regloom.dsphotword;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Gives Settings the localized title and a live summary of our entry in Settings → System
 * (meta-data com.android.settings.title_uri / summary_uri, see SettingsLib TileUtils).
 *
 * <p>Settings can't load resources from this APK itself: KernelSU hides module files from the
 * Settings process. This provider runs in our process, which can, so the entry follows the system
 * language and shows whether listening and voice match are on.
 */
public class SettingsTileProvider extends ContentProvider {
    private static final String KEY_TITLE = "com.android.settings.title";
    private static final String KEY_SUMMARY = "com.android.settings.summary";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("getTitle".equals(method)) {
            result.putString(KEY_TITLE, getContext().getString(R.string.settings_title));
        } else if ("getSummary".equals(method)) {
            int summary;
            if (!TriggerPrefs.isEnabled(getContext())) {
                summary = R.string.settings_summary_off;
            } else if (TriggerPrefs.isVoiceMatch(getContext())) {
                summary = R.string.settings_summary_on_voice;
            } else {
                summary = R.string.settings_summary_on;
            }
            result.putString(KEY_SUMMARY, getContext().getString(summary));
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
