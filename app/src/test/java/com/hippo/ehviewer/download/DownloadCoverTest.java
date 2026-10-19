package com.hippo.ehviewer.download;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ImageBitmapHelper;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Cover replacement preserves the last usable file until a complete replacement is available. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = {DownloadCoverTest.DownloadDirectory.class, DownloadCoverTest.ImageCache.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DownloadCoverTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private DownloadInfo info;
    private File directory;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        directory = temporary.newFolder("gallery");
        DownloadDirectory.directory = UniFile.fromFile(directory);
        ImageCache.cache = null;
        info = new DownloadInfo(42);
    }

    private File image(String name, int color) throws Exception {
        File file = temporary.newFile(name);
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private byte[] cover() throws IOException {
        return Files.readAllBytes(new File(directory, ".thumb").toPath());
    }

    @Test public void missingAndNonImageReplacementsPreserveOldCover() throws Exception {
        File original = image("original.png", Color.RED);
        DownloadCover.replace(context, info, original);
        byte[] expected = cover();
        File invalid = temporary.newFile("error.html");
        Files.write(invalid.toPath(), "<html>download failed</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (File source : new File[]{new File(temporary.getRoot(), "missing.png"), invalid}) {
            try {
                DownloadCover.replace(context, info, source);
                fail("Unavailable or non-image replacement must fail");
            } catch (IOException expectedFailure) {
                assertArrayEquals(expected, cover());
            }
        }
    }

    @Test public void delayedBackgroundSavePreservesExplicitCover() throws Exception {
        File old = image("old.png", Color.RED);
        File refreshed = image("refreshed.png", Color.BLUE);
        DownloadCover.saveIfMissing(context, info, old);
        DownloadCover.replace(context, info, refreshed);
        DownloadCover.saveIfMissing(context, info, old);
        assertArrayEquals(Files.readAllBytes(refreshed.toPath()), cover());
    }

    @Test public void consecutiveReplacementsLeaveLatestUsableImage() throws Exception {
        for (int color : new int[]{Color.RED, Color.GREEN, Color.BLUE}) {
            File source = image("cover-" + color + ".png", color);
            DownloadCover.replace(context, info, source);
            assertArrayEquals(Files.readAllBytes(source.toPath()), cover());
            DownloadCover.validate(context, new File(directory, ".thumb"));
        }
    }

    /** Supply an isolated gallery directory without initializing the app's real download database. */
    @Implements(SpiderDen.class)
    public static class DownloadDirectory {
        static UniFile directory;
        @Implementation public static UniFile getGalleryDownloadDir(GalleryInfo info) {
            return directory;
        }
    }

    /** Keep actual cache eviction available without booting native image/application services. */
    @Implements(EhApplication.class)
    public static class ImageCache {
        static Conaco<Image> cache;
        @Implementation public static Conaco<Image> getConaco(Context context) {
            if (cache == null) {
                Conaco.Builder<Image> builder = new Conaco.Builder<>();
                builder.hasDiskCache = true;
                builder.diskCacheDir = new File(context.getCacheDir(), "cover-test-cache");
                builder.diskCacheMaxSize = 1024 * 1024;
                builder.okHttpClient = new OkHttpClient();
                builder.objectHelper = new ImageBitmapHelper();
                cache = builder.build();
            }
            return cache;
        }
    }
}
