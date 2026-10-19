package com.hippo.ehviewer.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceManager;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SettingsBackupTest {
    private SharedPreferences preferences;
    private StandardDatabase database;

    @Before
    public void setUp() {
        preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        preferences.edit().clear().commit();
        database = new StandardDatabase(SQLiteDatabase.create(null));
    }

    @After
    public void tearDown() {
        database.close();
        preferences.edit().clear().commit();
    }

    @Test
    public void roundTripPreservesEveryPreferenceTypeAndMergesKeys() throws Exception {
        preferences.edit().putBoolean("boolean", true).putInt("int", Integer.MIN_VALUE)
                .putLong("long", Long.MAX_VALUE).putFloat("float", 0.12345679f)
                .putString("string", "中文\n\"\\").putString("empty", "")
                .putStringSet("set", new HashSet<>(Arrays.asList("a", "中文", "\"\\")))
                .putStringSet("empty_set", new HashSet<>()).commit();
        Map<String, ?> original = preferences.getAll();
        SettingsBackup.exportSettings(RuntimeEnvironment.getApplication(), database);
        preferences.edit().clear().putInt("int", 7).putString("new_setting", "keep").commit();

        SettingsBackup.importSettings(RuntimeEnvironment.getApplication(), database);

        for (Map.Entry<String, ?> entry : original.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(), preferences.getAll().get(entry.getKey()));
        }
        assertEquals("keep", preferences.getString("new_setting", null));
    }

    @Test
    public void oldBackupWithoutSettingsLeavesPreferencesUntouched() throws Exception {
        preferences.edit().putInt("keep", 42).commit();
        Map<String, ?> original = preferences.getAll();
        SettingsBackup.importSettings(RuntimeEnvironment.getApplication(), database);
        assertEquals(original, preferences.getAll());
    }

    @Test
    public void malformedTypedValuesNeverPartiallyCommit() throws Exception {
        preferences.edit().putString("keep", "original").commit();
        SettingsBackup.exportSettings(RuntimeEnvironment.getApplication(), database);
        Map<String, ?> original = preferences.getAll();
        for (String[] invalid : new String[][]{{"int", ""}, {"boolean", "yes"}, {"set", "["}, {"unknown", "value"}}) {
            database.execSQL("DELETE FROM SETTINGS_BACKUP");
            database.execSQL("INSERT INTO SETTINGS_BACKUP VALUES ('first', 'string', 'new')");
            database.execSQL("INSERT INTO SETTINGS_BACKUP VALUES ('bad', ?, ?)", invalid);
            try {
                SettingsBackup.importSettings(RuntimeEnvironment.getApplication(), database);
                fail("Invalid typed backup must fail");
            } catch (IllegalArgumentException | org.json.JSONException expected) {
                assertEquals(original, preferences.getAll());
            }
        }
    }
}
