package com.hippo.ehviewer.download;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.scene.download.part.DownloadListProjection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Imported archives are measured at their source, and every explicit refresh observes changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class DownloadFileSizesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importedArchivesRefreshGrowthEmptyFilesAndDeletion() throws Exception {
        ArchiveProvider provider = new ArchiveProvider();
        ShadowContentResolver.registerProviderInternal("archives", provider);
        DownloadInfo document = new DownloadInfo(1);
        document.archiveUri = "content://archives/book.zip";
        document.fileSize = 999;
        File archive = temporary.newFile("book.zip");
        try (FileOutputStream output = new FileOutputStream(archive)) {
            output.write(new byte[7]);
        }
        DownloadInfo local = new DownloadInfo(2);
        // Host file URIs must use the host platform's encoding (including Windows drives).
        local.archiveUri = archive.toURI().toString();
        List<DownloadInfo> source = Arrays.asList(document, local);

        provider.size = 23L;
        DownloadListProjection.loadFileSizes(RuntimeEnvironment.application, source);
        assertEquals(23L, document.fileSize);
        assertEquals(7L, local.fileSize);
        provider.size = 45L;
        try (FileOutputStream output = new FileOutputStream(archive)) {
            output.write(new byte[11]);
        }
        DownloadListProjection.loadFileSizes(RuntimeEnvironment.application, source);
        assertEquals(45L, document.fileSize);
        assertEquals(11L, local.fileSize);
        provider.size = 0L;
        new FileOutputStream(archive).close();
        DownloadListProjection.loadFileSizes(RuntimeEnvironment.application, source);
        assertEquals(0L, document.fileSize);
        assertEquals(0L, local.fileSize);
        provider.size = null;
        assertTrue(archive.delete());
        DownloadListProjection.loadFileSizes(RuntimeEnvironment.application, source);
        assertEquals(-1L, document.fileSize);
        assertEquals(-1L, local.fileSize);
    }

    public static class ArchiveProvider extends ContentProvider {
        Long size;

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
            cursor.addRow(new Object[]{size});
            return cursor;
        }

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "application/zip"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
