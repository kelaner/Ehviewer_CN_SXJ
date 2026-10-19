package com.hippo.ehviewer.download;

import android.content.Context;
import android.database.Cursor;
import android.util.LruCache;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.DownloadInfo;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Download metadata stored alongside, but independently of, the legacy DAO schema. */
public final class DownloadTags {
    private static final String TAGS = "DOWNLOAD_EXT_TAGS";
    private static final String METADATA = "DOWNLOAD_EXT_METADATA";
    private static final String CONFIG = "DOWNLOAD_EXT_CONFIG";
    private static Database database;
    private static final LruCache<Long, Snapshot> snapshots = new LruCache<>(512);

    private static final class Snapshot {
        final List<Tag> raw = new ArrayList<>();
        final List<Tag> local = new ArrayList<>();
        final List<Tag> all = new ArrayList<>();
    }

    public static synchronized void invalidate(long gid) {
        snapshots.remove(gid);
    }

    /** Keep a full download scan resident; capacity remains bounded by the library size. */
    public static synchronized void onDownloadsChanged() {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM DOWNLOADS", null)) {
            if (cursor.moveToFirst()) snapshots.resize(Math.max(512, cursor.getInt(0)));
        }
    }

    private DownloadTags() {}

    public static final class Tag {
        public final String namespace;
        public final String raw;

        public Tag(String namespace, String raw) {
            String ns = normalize(namespace);
            String alias = EhTagDatabase.prefixToNamespace(ns + ":");
            this.namespace = alias == null ? ns : alias;
            this.raw = raw == null ? "" : raw.trim();
        }

        public String identity() {
            return namespace + ":" + normalize(raw);
        }

        public String getDisplayName(Context context) {
            return Settings.getShowTagTranslations() ? getTranslation(context) : raw;
        }

        private String getTranslation(Context context) {
            EhTagDatabase translations = EhTagDatabase.getInstance(context);
            String prefix = EhTagDatabase.namespaceToPrefix(namespace);
            String translated = translations == null || prefix == null ? null
                    : translations.getTranslation(prefix + normalize(raw));
            return translated == null ? raw : translated;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Tag && identity().equals(((Tag) other).identity());
        }

        @Override
        public int hashCode() {
            return identity().hashCode();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Called once by EhDB, without changing user_version or any existing table. */
    public static synchronized void initialize(Database db) {
        createTables(db);
        database = db;
        snapshots.evictAll();
        onDownloadsChanged();
    }

    private static void createTables(Database db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TAGS + " (GID INTEGER NOT NULL, NAMESPACE TEXT NOT NULL, RAW TEXT NOT NULL, IDENTITY TEXT NOT NULL, LOCAL INTEGER NOT NULL, PRIMARY KEY (GID,IDENTITY,LOCAL))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + METADATA + " (GID INTEGER PRIMARY KEY NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CONFIG + " (KEY TEXT PRIMARY KEY NOT NULL, VALUE TEXT NOT NULL)");
    }

    private static void insert(Database db, long gid, Tag tag, boolean local) {
        if (tag.namespace.isEmpty() || tag.raw.isEmpty()) return;
        if (db == database) invalidate(gid);
        db.execSQL("INSERT OR IGNORE INTO " + TAGS + " (GID,NAMESPACE,RAW,IDENTITY,LOCAL) VALUES (?,?,?,?,?)",
                new Object[]{gid, tag.namespace, tag.raw, tag.identity(), local ? 1 : 0});
    }

    /** A completed response with zero tags is also known metadata. */
    public static synchronized void cacheRawTags(GalleryDetail detail) {
        if (detail == null || !exists("DOWNLOADS", detail.gid)) return;
        invalidate(detail.gid);
        database.beginTransaction();
        try {
            database.execSQL("DELETE FROM " + TAGS + " WHERE GID=? AND LOCAL=0", new Object[]{detail.gid});
            if (detail.tags != null) {
                for (GalleryTagGroup group : detail.tags) {
                    for (int i = 0; i < group.size(); i++) {
                        insert(database, detail.gid, new Tag(group.groupName, group.getTagAt(i)), false);
                    }
                }
            }
            removeDuplicateLocalTags(database, detail.gid);
            database.execSQL("INSERT OR IGNORE INTO " + METADATA + " (GID) VALUES (?)", new Object[]{detail.gid});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public static boolean cacheFromDetailCache(Context context, long gid) {
        GalleryDetail detail = EhApplication.getGalleryDetailCache(context).get(gid);
        if (detail == null) return false;
        cacheRawTags(detail);
        return hasRawTags(gid);
    }

    private static boolean exists(String table, long gid) {
        return exists(database, table, gid);
    }

    private static boolean exists(Database db, String table, long gid) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM " + table + " WHERE GID=? LIMIT 1", new String[]{Long.toString(gid)})) {
            return cursor.moveToFirst();
        }
    }

    // Legacy values are raw comma-separated strings written by GalleryDetailTagsSyncTask.
    private static void migrateLegacy(long gid) {
        if (exists(METADATA, gid)) return;
        migrateLegacy(database, gid);
        removeDuplicateLocalTags(database, gid);
    }

    private static void migrateLegacy(Database db, long gid) {
        if (exists(db, METADATA, gid) || !exists(db, "DOWNLOADS", gid)) return;
        mergeLegacy(db, db, gid);
    }

    private static void mergeLegacy(Database source, Database target, long gid) {
        String[] namespaces = {"rows", "artist", "cosplayer", "character", "female", "group", "language", "male", "misc", "mixed", "other", "parody", "reclass", "location"};
        try (Cursor cursor = source.rawQuery("SELECT ROWS,ARTIST,COSPLAYER,CHARACTER,FEMALE,\"GROUP\",LANGUAGE,MALE,MISC,MIXED,OTHER,PARODY,RECLASS,LOCATION FROM Gallery_Tags WHERE GID=?", new String[]{Long.toString(gid)})) {
            if (!cursor.moveToFirst()) return;
            for (int i = 0; i < namespaces.length; i++) {
                String values = cursor.getString(i);
                if (values != null) {
                    for (String raw : values.split(",")) insert(target, gid, new Tag(namespaces[i], raw), false);
                }
            }
        }
        // Legacy list summaries can be partial; only a full detail response marks metadata complete.
    }

    public static synchronized boolean hasRawTags(long gid) {
        return exists(METADATA, gid);
    }

    public static synchronized List<Tag> getRawTags(long gid) {
        return new ArrayList<>(snapshot(gid).raw);
    }

    public static synchronized List<Tag> getLocalTags(long gid) {
        return new ArrayList<>(snapshot(gid).local);
    }

    public static synchronized List<Tag> getTags(long gid) {
        return new ArrayList<>(snapshot(gid).all);
    }

    private static Snapshot snapshot(long gid) {
        Snapshot cached = snapshots.get(gid);
        if (cached != null) return cached;
        migrateLegacy(gid);
        Snapshot result = new Snapshot();
        LinkedHashMap<String, Tag> merged = new LinkedHashMap<>();
        try (Cursor cursor = database.rawQuery("SELECT NAMESPACE,RAW,LOCAL FROM " + TAGS + " WHERE GID=? ORDER BY NAMESPACE,RAW", new String[]{Long.toString(gid)})) {
            while (cursor.moveToNext()) {
                Tag tag = new Tag(cursor.getString(0), cursor.getString(1));
                (cursor.getInt(2) == 0 ? result.raw : result.local).add(tag);
                merged.put(tag.identity(), tag);
            }
        }
        result.all.addAll(merged.values());
        snapshots.put(gid, result);
        return result;
    }

    private static boolean finished(long gid) {
        try (Cursor cursor = database.rawQuery("SELECT STATE FROM DOWNLOADS WHERE GID=?", new String[]{Long.toString(gid)})) {
            return cursor.moveToFirst() && cursor.getInt(0) == DownloadInfo.STATE_FINISH;
        }
    }

    /** Each selected item is checked separately; duplicates and unfinished items are skipped. */
    public static synchronized int addLocalTags(List<DownloadInfo> infos, String namespace, String raw) {
        Tag tag = new Tag(namespace, raw);
        if (!(tag.namespace.equals("artist") || tag.namespace.equals("character") || tag.namespace.equals("group")) || tag.raw.isEmpty()) return 0;
        int changed = 0;
        database.beginTransaction();
        try {
            for (DownloadInfo info : infos) {
                if (info == null || info.state != DownloadInfo.STATE_FINISH || !finished(info.gid)) continue;
                if (getTags(info.gid).contains(tag)) continue;
                insert(database, info.gid, tag, true);
                changed++;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
            for (DownloadInfo info : infos) {
                if (info != null) invalidate(info.gid);
            }
        }
        return changed;
    }

    public static synchronized int clearLocalTags(List<DownloadInfo> infos) {
        int changed = 0;
        database.beginTransaction();
        try {
            for (DownloadInfo info : infos) {
                if (info == null || info.state != DownloadInfo.STATE_FINISH || !finished(info.gid) || getLocalTags(info.gid).isEmpty()) continue;
                database.execSQL("DELETE FROM " + TAGS + " WHERE GID=? AND LOCAL=1", new Object[]{info.gid});
                invalidate(info.gid);
                changed++;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
            for (DownloadInfo info : infos) {
                if (info != null) invalidate(info.gid);
            }
        }
        return changed;
    }

    public static boolean matches(Context context, long gid, String keyword) {
        String query = normalize(keyword);
        if (query.isEmpty()) return true;
        for (Tag tag : getTags(gid)) {
            if (tag.identity().contains(query) || normalize(tag.getTranslation(context)).contains(query)) return true;
        }
        return false;
    }

    public static synchronized String getConfig(String key, String defaultValue) {
        try (Cursor cursor = database.rawQuery("SELECT VALUE FROM " + CONFIG + " WHERE KEY=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : defaultValue;
        }
    }

    public static synchronized void setConfig(String key, String value) {
        if (value == null) {
            database.execSQL("DELETE FROM " + CONFIG + " WHERE KEY=?", new Object[]{key});
        } else {
            database.execSQL("INSERT OR REPLACE INTO " + CONFIG + " (KEY,VALUE) VALUES (?,?)", new Object[]{key, value});
        }
    }

    public static synchronized void remove(long gid) {
        invalidate(gid);
        database.execSQL("DELETE FROM " + TAGS + " WHERE GID=?", new Object[]{gid});
        database.execSQL("DELETE FROM " + METADATA + " WHERE GID=?", new Object[]{gid});
        onDownloadsChanged();
    }

    private static void removeDuplicateLocalTags(Database db, Long gid) {
        db.execSQL("DELETE FROM " + TAGS + " WHERE LOCAL=1" + (gid == null ? "" : " AND GID=?")
                + " AND EXISTS (SELECT 1 FROM " + TAGS
                + " AS original WHERE original.GID=" + TAGS + ".GID AND original.IDENTITY=" + TAGS
                + ".IDENTITY AND original.LOCAL=0)", gid == null ? new Object[0] : new Object[]{gid});
    }

    private static boolean hasDownload(Database db, long gid) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM DOWNLOADS WHERE GID=?", new String[]{Long.toString(gid)})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean tableExists(Database db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    /** Merge tags, normalize identities, and keep existing machine configuration on import. */
    public static synchronized void copy(Database source, Database target) {
        createTables(target);
        target.beginTransaction();
        try {
            if (tableExists(source, TAGS)) {
                try (Cursor cursor = source.rawQuery("SELECT GID,NAMESPACE,RAW,LOCAL FROM " + TAGS, null)) {
                    while (cursor.moveToNext()) {
                        long gid = cursor.getLong(0);
                        if (hasDownload(target, gid)) {
                            insert(target, gid, new Tag(cursor.getString(1), cursor.getString(2)), cursor.getInt(3) != 0);
                        }
                    }
                }
            }
            boolean sourceHasMetadata = tableExists(source, METADATA);
            if (tableExists(source, "Gallery_Tags")) {
                try (Cursor cursor = source.rawQuery("SELECT GID FROM Gallery_Tags", null)) {
                    while (cursor.moveToNext()) {
                        long gid = cursor.getLong(0);
                        if (hasDownload(target, gid) && (!sourceHasMetadata || !exists(source, METADATA, gid))) {
                            mergeLegacy(source, target, gid);
                        }
                    }
                }
            }
            if (sourceHasMetadata) {
                try (Cursor cursor = source.rawQuery("SELECT GID FROM " + METADATA, null)) {
                    while (cursor.moveToNext()) {
                        long gid = cursor.getLong(0);
                        if (hasDownload(target, gid)) target.execSQL("INSERT OR IGNORE INTO " + METADATA + " (GID) VALUES (?)", new Object[]{gid});
                    }
                }
            }
            if (tableExists(source, CONFIG)) {
                try (Cursor cursor = source.rawQuery("SELECT KEY,VALUE FROM " + CONFIG, null)) {
                    while (cursor.moveToNext()) target.execSQL("INSERT OR IGNORE INTO " + CONFIG + " (KEY,VALUE) VALUES (?,?)", new Object[]{cursor.getString(0), cursor.getString(1)});
                }
            }
            try (Cursor cursor = target.rawQuery("SELECT GID FROM DOWNLOADS", null)) {
                while (cursor.moveToNext()) migrateLegacy(target, cursor.getLong(0));
            }
            removeDuplicateLocalTags(target, null);
            target.setTransactionSuccessful();
        } finally {
            target.endTransaction();
            if (target == database) {
                snapshots.evictAll();
                onDownloadsChanged();
            }
        }
    }
}
