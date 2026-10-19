package com.hippo.ehviewer.download;

import android.database.Cursor;

import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;

/** Gallery fields absent from the legacy download DAO, stored in the same database. */
public final class DownloadMetadata {
    private static final String TABLE = "DOWNLOAD_GALLERY_METADATA";

    private DownloadMetadata() {}

    public static void initialize(Database database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (GID INTEGER PRIMARY KEY NOT NULL, JSON TEXT NOT NULL)");
    }

    /** Copy only gallery fields; download progress and grouping are never touched. */
    public static void apply(GalleryInfo target, GalleryInfo source) {
        target.token = source.token;
        target.title = source.title;
        target.titleJpn = source.titleJpn;
        target.thumb = source.thumb;
        target.category = source.category;
        target.posted = source.posted;
        target.uploader = source.uploader;
        target.rating = source.rating;
        target.rated = source.rated;
        target.simpleTags = source.simpleTags == null ? null : source.simpleTags.clone();
        if (source.pages > 0) target.pages = source.pages;
        target.thumbWidth = source.thumbWidth;
        target.thumbHeight = source.thumbHeight;
        target.spanSize = source.spanSize;
        target.spanIndex = source.spanIndex;
        target.spanGroupIndex = source.spanGroupIndex;
        target.tgList = source.tgList == null ? null : new ArrayList<>(source.tgList);
        target.simpleLanguage = source.simpleLanguage;
        target.favoriteSlot = source.favoriteSlot;
        target.favoriteName = source.favoriteName;
    }

    public static GalleryInfo fromDetail(GalleryInfo original, GalleryDetail detail) {
        GalleryInfo snapshot = new GalleryInfo();
        snapshot.gid = original.gid;
        apply(snapshot, original);
        apply(snapshot, detail);
        // Detail responses carry tag groups rather than the list API's simpleTags.
        if (detail.tags != null) {
            ArrayList<String> tags = new ArrayList<>();
            for (GalleryTagGroup group : detail.tags) {
                for (int i = 0; i < group.size(); i++) {
                    tags.add(group.groupName + ":" + group.getTagAt(i));
                }
            }
            snapshot.simpleTags = tags.toArray(new String[0]);
        }
        snapshot.simpleLanguage = null;
        snapshot.generateSLang();
        return snapshot;
    }

    public static void save(Database database, GalleryInfo info) {
        database.execSQL("INSERT OR REPLACE INTO " + TABLE + " (GID,JSON) VALUES (?,?)",
                new Object[]{info.gid, JSON.toJSONString(info)});
    }

    public static void restore(Database database, GalleryInfo info) {
        try (Cursor cursor = database.rawQuery("SELECT JSON FROM " + TABLE + " WHERE GID=?",
                new String[]{Long.toString(info.gid)})) {
            if (cursor.moveToFirst()) {
                GalleryInfo saved = JSON.parseObject(cursor.getString(0), GalleryInfo.class);
                if (saved != null && saved.gid == info.gid) apply(info, saved);
            }
        }
    }

    public static void remove(Database database, long gid) {
        database.execSQL("DELETE FROM " + TABLE + " WHERE GID=?", new Object[]{gid});
    }

    public static void copy(Database source, Database target) {
        initialize(target);
        try (Cursor tables = source.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{TABLE})) {
            if (!tables.moveToFirst()) return;
        }
        try (Cursor cursor = source.rawQuery("SELECT GID,JSON FROM " + TABLE, null)) {
            while (cursor.moveToNext()) {
                target.execSQL("INSERT OR IGNORE INTO " + TABLE + " (GID,JSON) SELECT ?,? WHERE EXISTS (SELECT 1 FROM DOWNLOADS WHERE GID=?)",
                        new Object[]{cursor.getLong(0), cursor.getString(1), cursor.getLong(0)});
            }
        }
    }
}
