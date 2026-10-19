package com.hippo.ehviewer.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.preference.PreferenceManager;

import org.greenrobot.greendao.database.Database;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Typed default preferences stored in the same database as the exported data. */
public final class SettingsBackup {
    private static final String TABLE = "SETTINGS_BACKUP";

    private SettingsBackup() {}

    public static void exportSettings(Context context, Database db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (NAME TEXT PRIMARY KEY, TYPE TEXT NOT NULL, VALUE TEXT NOT NULL)");
        db.execSQL("DELETE FROM " + TABLE);
        for (Map.Entry<String, ?> entry : PreferenceManager.getDefaultSharedPreferences(context).getAll().entrySet()) {
            Object value = entry.getValue();
            String type;
            String text;
            if (value instanceof Set) {
                type = "set";
                text = new JSONArray((Set<?>) value).toString();
            } else {
                if (value instanceof Boolean) type = "boolean";
                else if (value instanceof Integer) type = "int";
                else if (value instanceof Long) type = "long";
                else if (value instanceof Float) type = "float";
                else if (value instanceof String) type = "string";
                else continue;
                text = value.toString();
            }
            db.execSQL("INSERT OR REPLACE INTO " + TABLE + " VALUES (?, ?, ?)", new Object[]{entry.getKey(), type, text});
        }
    }

    public static void importSettings(Context context, Database db) throws JSONException {
        try (Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{TABLE})) {
            if (!tables.moveToFirst()) return; // Older backups contain database records only.
        }
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        try (Cursor cursor = db.rawQuery("SELECT NAME, TYPE, VALUE FROM " + TABLE, null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(2);
                switch (cursor.getString(1)) {
                    case "boolean":
                        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid boolean setting");
                        editor.putBoolean(key, Boolean.parseBoolean(value));
                        break;
                    case "int": editor.putInt(key, Integer.parseInt(value)); break;
                    case "long": editor.putLong(key, Long.parseLong(value)); break;
                    case "float": editor.putFloat(key, Float.parseFloat(value)); break;
                    case "string": editor.putString(key, value); break;
                    case "set":
                        JSONArray array = new JSONArray(value);
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < array.length(); i++) set.add(array.getString(i));
                        editor.putStringSet(key, set);
                        break;
                    default: throw new IllegalArgumentException("Unsupported settings type");
                }
            }
        }
        if (!editor.commit()) throw new IllegalStateException("Failed to restore settings");
    }
}
