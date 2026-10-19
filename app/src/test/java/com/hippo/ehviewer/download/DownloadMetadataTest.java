package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.DownloadInfo;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DownloadMetadataTest {
    private StandardDatabase database;
    private DaoSession session;
    private Field sessionField;
    private Object previousSession;

    @Before
    public void setUp() throws Exception {
        database = new StandardDatabase(SQLiteDatabase.create(null));
        DaoMaster.createAllTables(database, false);
        session = new DaoMaster(database).newSession();
        sessionField = EhDB.class.getDeclaredField("sDaoSession");
        sessionField.setAccessible(true);
        previousSession = sessionField.get(null);
        sessionField.set(null, session);
        DownloadTags.initialize(database);
        DownloadMetadata.initialize(database);
    }

    @After
    public void tearDown() throws Exception {
        sessionField.set(null, previousSession);
        database.close();
    }

    private DownloadInfo download() {
        DownloadInfo info = new DownloadInfo(42);
        info.title = "old";
        info.pages = 20;
        info.state = DownloadInfo.STATE_FINISH;
        info.label = "keep";
        info.time = 123;
        info.total = 20;
        info.finished = 19;
        info.downloaded = 18;
        info.archiveUri = "content://archive";
        EhDB.putDownloadInfo(info);
        return info;
    }

    private GalleryDetail detail() {
        GalleryDetail detail = new GalleryDetail();
        detail.gid = 42;
        detail.token = "new-token";
        detail.title = "新标题";
        detail.titleJpn = "日本語";
        detail.thumb = "https://example.invalid/new.jpg";
        detail.pages = 35;
        detail.category = 4;
        detail.posted = "2026-10-19";
        detail.uploader = "uploader";
        detail.rating = 4.5f;
        detail.rated = true;
        detail.thumbWidth = 100;
        detail.thumbHeight = 150;
        detail.favoriteSlot = 3;
        detail.favoriteName = "favorites";
        detail.tgList = new ArrayList<>(Arrays.asList("artist:sample"));
        GalleryTagGroup group = new GalleryTagGroup();
        group.groupName = "language";
        group.addTag("chinese");
        detail.tags = new GalleryTagGroup[]{group};
        return detail;
    }

    @Test
    public void refreshSurvivesReloadAndPreservesDownloadProgress() throws Exception {
        DownloadInfo info = download();
        GalleryDetail detail = detail();
        EhDB.updateDownloadMetadata(info, detail);
        assertEquals(35, info.pages);
        assertEquals(20, info.total);
        assertEquals(19, info.finished);
        assertEquals(18, info.downloaded);
        assertEquals(DownloadInfo.STATE_FINISH, info.state);
        assertEquals("keep", info.label);
        assertEquals(123, info.time);
        assertEquals("content://archive", info.archiveUri);
        // A fresh DAO session cannot reuse the shared download instance.
        DaoSession reopened = new DaoMaster(database).newSession();
        sessionField.set(null, reopened);
        assertEquals(detail.title, reopened.getDownloadsDao().load(42L).title);
        DownloadInfo restored = EhDB.getAllDownloadInfo().get(0);
        assertEquals(detail.title, restored.title);
        assertEquals(detail.titleJpn, restored.titleJpn);
        assertEquals(detail.thumb, restored.thumb);
        assertEquals(detail.token, restored.token);
        assertEquals(detail.category, restored.category);
        assertEquals(detail.posted, restored.posted);
        assertEquals(detail.uploader, restored.uploader);
        assertEquals(detail.rating, restored.rating, 0);
        assertTrue(restored.rated);
        assertEquals(35, restored.pages);
        assertArrayEquals(new String[]{"language:chinese"}, restored.simpleTags);
        assertEquals("ZH", restored.simpleLanguage);
        assertEquals(100, restored.thumbWidth);
        assertEquals(150, restored.thumbHeight);
        assertEquals(3, restored.favoriteSlot);
        assertEquals("favorites", restored.favoriteName);
        assertEquals(detail.tgList, restored.tgList);
        assertTrue(DownloadTags.hasRawTags(42));
        assertEquals("chinese", DownloadTags.getRawTags(42).get(0).raw);
    }

    @Test
    public void failedTagCommitLeavesSharedAndPersistedMetadataUntouched() {
        DownloadInfo info = download();
        GalleryDetail original = detail();
        EhDB.updateDownloadMetadata(info, original);
        // Model a storage failure after normal fields and sidecar have been written.
        database.execSQL("CREATE TRIGGER reject_tags BEFORE INSERT ON DOWNLOAD_EXT_TAGS BEGIN SELECT RAISE(ABORT, 'storage failure'); END");
        GalleryDetail replacement = detail();
        replacement.title = "must not commit";
        replacement.pages = 99;
        try {
            EhDB.updateDownloadMetadata(info, replacement);
            fail("A failed tag write must abort the refresh");
        } catch (android.database.SQLException expected) {
            assertEquals(original.title, info.title);
            assertEquals(35, info.pages);
            session.clear();
            assertEquals(original.title, session.getDownloadsDao().load(42L).title);
            DownloadInfo restored = EhDB.getAllDownloadInfo().get(0);
            assertEquals(original.title, restored.title);
            assertEquals(35, restored.pages);
            assertTrue(DownloadTags.hasRawTags(42));
            assertEquals("chinese", DownloadTags.getRawTags(42).get(0).raw);
        }
    }

    @Test
    public void backupCopyPreservesMetadataAndDoesNotReplaceExistingDownloads() {
        DownloadInfo info = download();
        EhDB.updateDownloadMetadata(info, detail());
        StandardDatabase backup = new StandardDatabase(SQLiteDatabase.create(null));
        try {
            DaoMaster.createAllTables(backup, false);
            new DaoMaster(backup).newSession().getDownloadsDao().insert(new DownloadInfo(42));
            DownloadMetadata.copy(database, backup);
            DownloadInfo restored = new DownloadInfo(42);
            DownloadMetadata.restore(backup, restored);
            assertEquals(35, restored.pages);
            assertEquals(info.title, restored.title);
            GalleryDetail newer = detail();
            newer.title = "current download wins";
            EhDB.updateDownloadMetadata(info, newer);
            DownloadMetadata.copy(backup, database);
            session.clear();
            assertEquals(newer.title, EhDB.getAllDownloadInfo().get(0).title);
        } finally {
            backup.close();
        }
    }

    @Test
    public void unknownPageCountDoesNotEraseKnownCountAndDeleteCleansMetadata() {
        DownloadInfo info = download();
        GalleryDetail detail = detail();
        detail.pages = 0;
        detail.tags = new GalleryTagGroup[0];
        EhDB.updateDownloadMetadata(info, detail);
        session.clear();
        assertEquals(20, EhDB.getAllDownloadInfo().get(0).pages);
        assertTrue(DownloadTags.hasRawTags(42));
        assertTrue(DownloadTags.getRawTags(42).isEmpty());
        EhDB.removeDownloadInfo(42);
        EhDB.putDownloadInfo(new DownloadInfo(42));
        session.clear();
        DownloadInfo replacement = EhDB.getAllDownloadInfo().get(0);
        assertEquals(0, replacement.pages);
        assertNull(replacement.simpleTags);
        assertFalse(DownloadTags.hasRawTags(42));
    }
}
