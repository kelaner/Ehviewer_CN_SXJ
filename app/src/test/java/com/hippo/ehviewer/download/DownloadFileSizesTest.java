package com.hippo.ehviewer.download;

import android.app.Application;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.ui.scene.download.part.DownloadListProjection;
import com.hippo.unifile.UniFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;

/** Public sorting results after reading actual persisted download directories. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = DownloadFileSizesTest.DownloadDirectories.class)
public class DownloadFileSizesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void originalDirectorySizesSortInBothDirections() throws Exception {
        DownloadDirectories.directories.clear();
        DownloadInfo small = download(10);
        DownloadInfo large = download(20);
        DownloadInfo unknown = download(30);
        File smallDir = directory(small);
        Files.write(new File(smallDir, "00000001.jpg").toPath(), new byte[7]);
        File largeDir = directory(large);
        Files.write(new File(largeDir, "00000001.jpg").toPath(), new byte[11]);
        File nested = new File(largeDir, "nested");
        org.junit.Assert.assertTrue(nested.mkdir());
        Files.write(new File(nested, "00000002.jpg").toPath(), new byte[13]);
        List<DownloadInfo> source = Arrays.asList(unknown, large, small);
        DownloadListProjection.loadFileSizes(source);
        assertEquals(7, small.fileSize);
        assertEquals(24, large.fileSize);
        assertEquals(-1, unknown.fileSize);
        DownloadListProjection projection = new DownloadListProjection();
        projection.select(R.id.sort_by_file_size_asc);
        assertEquals(Arrays.asList(small, large, unknown), projection.apply(null, source, null));
        projection.select(R.id.sort_by_file_size_desc);
        assertEquals(Arrays.asList(large, small, unknown), projection.apply(null, source, null));
        assertEquals(Arrays.asList(unknown, large, small), source);
    }

    @Test public void restartRestoresPagesForAllEntriesBeforeSorting() throws Exception {
        DownloadDirectories.directories.clear();
        DownloadInfo shortBook = download(10);
        DownloadInfo longBook = download(20);
        DownloadInfo unknown = download(30);
        writeHeader(directory(shortBook), 10, "token", 12);
        writeHeader(directory(longBook), 20, "token", 80);
        // A mismatched sidecar must not supply a plausible but incorrect count.
        writeHeader(directory(unknown), 999, "token", 1);
        List<DownloadInfo> source = Arrays.asList(unknown, longBook, shortBook);
        DownloadListProjection.loadPageCounts(RuntimeEnvironment.getApplication(), source);
        assertEquals(12, shortBook.pages);
        assertEquals(80, longBook.pages);
        DownloadListProjection projection = new DownloadListProjection();
        projection.select(R.id.sort_by_pages_asc);
        assertEquals(Arrays.asList(shortBook, longBook, unknown), projection.apply(null, source, null));
        projection.select(R.id.sort_by_pages_desc);
        assertEquals(Arrays.asList(longBook, shortBook, unknown), projection.apply(null, source, null));
    }

    private static DownloadInfo download(long gid) {
        DownloadInfo info = new DownloadInfo(gid);
        info.token = "token";
        info.state = DownloadInfo.STATE_FINISH;
        return info;
    }

    private File directory(DownloadInfo info) throws Exception {
        File folder = temporary.newFolder(Long.toString(info.gid));
        DownloadDirectories.directories.put(info.gid, UniFile.fromFile(folder));
        return folder;
    }

    private static void writeHeader(File folder, long gid, String token, int pages) throws Exception {
        String text = "VERSION2\n00000000\n" + gid + "\n" + token + "\nhttps://example.org\n1\n20\n" + pages + "\n";
        Files.write(new File(folder, ".ehviewer").toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Implements(SpiderDen.class)
    public static class DownloadDirectories {
        static final Map<Long, UniFile> directories = new HashMap<>();
        @Implementation public static UniFile getGalleryDownloadDir(GalleryInfo info) {
            return directories.get(info.gid);
        }
        @Implementation public static UniFile getExistingGalleryDownloadDir(GalleryInfo info) {
            return directories.get(info.gid);
        }
    }
}
