package com.hippo.ehviewer.download;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Looper;
import android.widget.FrameLayout;

import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ImageBitmapHelper;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.ui.scene.download.part.ThumbDataContainer;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.widget.LoadImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Durable download covers must work independently of the evictable shared caches. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = LocalCoverLoadingTest.TestApplication.class,
        shadows = {LocalCoverLoadingTest.DownloadDirectories.class, LocalCoverLoadingTest.ImageLoader.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LocalCoverLoadingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ActivityController<Activity> controller;
    private FrameLayout root;
    private CheckedImageView view;
    private Conaco<Image> conaco;
    private AtomicInteger requests;
    private byte[] red;
    private byte[] blue;
    private byte[] green;

    @Before public void setUp() throws Exception {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        Image.initialize((EhApplication) RuntimeEnvironment.getApplication());
        DownloadDirectories.directories.clear();
        DownloadDirectories.blockedGid = -1;
        requests = new AtomicInteger();
        red = png(Color.RED);
        blue = png(Color.BLUE);
        green = png(Color.GREEN);
        Conaco.Builder<Image> builder = new Conaco.Builder<>();
        builder.hasMemoryCache = true;
        builder.memoryCacheMaxSize = 8 * 8 * 4 * 4;
        builder.hasDiskCache = true;
        builder.diskCacheDir = temporary.newFolder("shared-cache");
        builder.diskCacheMaxSize = 1024 * 1024;
        builder.objectHelper = new ImageBitmapHelper();
        builder.okHttpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            if (chain.request().url().encodedPath().contains("offline")) {
                throw new IOException("Simulated offline connection");
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(MediaType.parse("image/png"), green)).build();
        }).build();
        conaco = builder.build();
        ImageLoader.conaco = conaco;
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        root = new FrameLayout(controller.get());
        controller.get().setContentView(root);
        view = new CheckedImageView(controller.get());
        root.addView(view, new FrameLayout.LayoutParams(8, 8));
    }

    @After public void tearDown() {
        if (DownloadDirectories.release != null) DownloadDirectories.release.countDown();
        if (view != null) view.unload();
        if (controller != null) controller.pause().stop().destroy();
        if (conaco != null) conaco.getBeerBelly().clearMemory();
        ImageLoader.conaco = null;
        DownloadDirectories.directories.clear();
    }

    @Test public void localBlueWinsOverMemoryRedWithoutReplacingSharedCache() throws Exception {
        DownloadInfo info = gallery(1, blue);
        Image cached = solid(Color.RED);
        assertTrue(conaco.getBeerBelly().putToMemory("same-key", cached));
        assertTrue(conaco.getBeerBelly().putRawToDisk("same-key", new ByteArrayInputStream(red)));
        view.load("same-key", "https://cover.invalid/unused", new ThumbDataContainer(info), true);
        await(view);
        assertPixels(view, Color.BLUE);
        assertSame("Local delivery must not replace the shared memory entry", cached,
                conaco.getBeerBelly().getFromMemory("same-key"));
        ByteArrayOutputStream disk = new ByteArrayOutputStream();
        assertTrue(conaco.getBeerBelly().pullFromDiskCache("same-key", disk));
        assertArrayEquals("Local delivery must not replace the shared disk entry", red, disk.toByteArray());
        assertEquals(0, requests.get());
    }

    @Test public void offlineLocalCoverSurvivesMoreThanAThousandRebindsCancelsAndReattachments() throws Exception {
        final int count = 1201;
        DownloadInfo[] galleries = new DownloadInfo[count];
        for (int i = 0; i < count; i++) galleries[i] = gallery(100 + i, (i & 1) == 0 ? blue : green);

        // Hold a real local read across cancellation, then rebind the same real widget.
        DownloadDirectories.blockedGid = galleries[0].gid;
        DownloadDirectories.entered = new CountDownLatch(1);
        DownloadDirectories.release = new CountDownLatch(1);
        view.load("cancelled", null, new ThumbDataContainer(galleries[0]), false);
        assertTrue("The old local request must actually be in flight",
                DownloadDirectories.entered.await(5, TimeUnit.SECONDS));
        view.unload();
        view.expectedColor = Color.GREEN;
        view.load("replacement", null, new ThumbDataContainer(galleries[1]), false);
        DownloadDirectories.release.countDown();
        await(view);
        assertPixels(view, Color.GREEN);
        DownloadDirectories.blockedGid = -1;

        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < count; j++) {
                int i = pass == 0 ? j : count - 1 - j;
                String key = "cover-" + i;
                // Cancel an obsolete binding before asking the recycled widget for this item.
                view.load("obsolete-" + j, null, new ThumbDataContainer(galleries[(i + 1) % count]), false);
                view.expectedColor = (i & 1) == 0 ? Color.BLUE : Color.GREEN;
                view.load(key, null, new ThumbDataContainer(galleries[i]), false);
                assertNull("A rebind must not show the previous item's cover while loading", view.getDrawable());
                if (j % 37 == 0) {
                    root.removeView(view);
                    assertNull("Detach must release the old drawable", view.getDrawable());
                    root.addView(view, new FrameLayout.LayoutParams(8, 8));
                }
                await(view);
                if (j % 11 == 0) {
                    // The tiny cache really evicts live Images here; clearing an empty cache proves nothing.
                    for (int p = 0; p < 8; p++) {
                        assertTrue(conaco.getBeerBelly().putToMemory("pressure-" + p, solid(Color.RED)));
                    }
                    conaco.getBeerBelly().clearMemory();
                }
                assertPixels(view, (i & 1) == 0 ? Color.BLUE : Color.GREEN);
                assertNull("Durable covers must not populate shared memory",
                        conaco.getBeerBelly().getFromMemory(key));
                assertNull("Durable covers must not populate shared disk",
                        conaco.getBeerBelly().getFromDisk(key));
            }
        }
        assertEquals("No remote URL or disk entry is needed for local covers", 0, requests.get());
    }

    // API 27 avoids host-Windows mmap file locks during atomic replacement; API 28 local reads
    // remain covered by the priority, cancellation and stress tests above.
    @Config(sdk = 27)
    @Test public void brokenOrUnreadableLocalCoversFallBackAndOfflineFailureIsIsolated() throws Exception {
        DownloadInfo broken = gallery(10, "<html>expired</html>".getBytes(StandardCharsets.UTF_8));
        // A directory cannot be opened as an image file on either Windows or Linux; chmod is not portable.
        DownloadInfo unreadable = gallery(11, null);
        File notAFile = new File(temporary.getRoot(), "gallery-" + unreadable.gid + "/.thumb");
        assertTrue(notAFile.mkdir());
        Files.write(new File(notAFile, "child").toPath(), new byte[]{1});

        for (DownloadInfo info : new DownloadInfo[]{broken, unreadable}) {
            String key = DownloadCover.getCacheKey(info.gid);
            assertTrue(conaco.getBeerBelly().putToMemory(key, solid(Color.RED)));
            assertTrue(conaco.getBeerBelly().putRawToDisk(key, new ByteArrayInputStream(green)));
            view.load(key, "https://cover.invalid/not-needed", new ThumbDataContainer(info), true);
            await(view);
            assertPixels(view, Color.GREEN);
            DownloadCover.validate(controller.get(), new File(temporary.getRoot(), "gallery-" + info.gid + "/.thumb"));
        }
        assertEquals("Shared disk fallback must repair without requesting the network", 0, requests.get());

        DownloadInfo diskOnly = gallery(15, "broken".getBytes(StandardCharsets.UTF_8));
        String diskKey = DownloadCover.getCacheKey(diskOnly.gid);
        assertTrue(conaco.getBeerBelly().putRawToDisk(diskKey, new ByteArrayInputStream(green)));
        view.load(diskKey, null, new ThumbDataContainer(diskOnly), false);
        await(view);
        assertPixels(view, Color.GREEN);
        DownloadCover.validate(controller.get(), new File(temporary.getRoot(), "gallery-15/.thumb"));
        conaco.getBeerBelly().clear();
        view.load(DownloadCover.getCacheKey(diskOnly.gid), null, new ThumbDataContainer(diskOnly), false);
        await(view);
        assertPixels(view, Color.GREEN);
        assertEquals(0, requests.get());

        DownloadInfo network = gallery(12, "broken".getBytes(StandardCharsets.UTF_8));
        view.load("network-fallback", "https://cover.invalid/green.png", new ThumbDataContainer(network), true);
        await(view);
        assertPixels(view, Color.GREEN);
        assertEquals("Exactly one network fallback is sufficient", 1, requests.get());

        DownloadInfo offline = gallery(13, "broken".getBytes(StandardCharsets.UTF_8));
        view.load("offline", "https://cover.invalid/offline", new ThumbDataContainer(offline), true);
        await(view);
        assertTrue("An unavailable local/cache/network image must fail, not hang", view.mFailed);
        assertEquals(2, requests.get());
        DownloadInfo available = gallery(14, blue);
        view.load("unrelated", null, new ThumbDataContainer(available), false);
        await(view);
        assertPixels(view, Color.BLUE);
        assertFalse(view.mFailed);
        assertEquals("Failure of another item must not cause an extra network call", 2, requests.get());
    }

    @Test public void validLocalCoverDoesNotWaitForSameKeyRemoteOrBusySharedCache() throws Exception {
        DownloadInfo info = gallery(31, blue);
        String key = DownloadCover.getCacheKey(info.gid);
        // A cache writer holds the same-key lock while a remote-only view queues its disk read.
        com.hippo.streampipe.OutputStreamPipe writer = conaco.getBeerBelly().getDiskCache().getOutputStreamPipe(key);
        writer.obtain();
        LoadImageView remote = new LoadImageView(controller.get());
        try {
            writer.open().write(red);
            remote.load(key, "https://cover.invalid/green.png");
            view.load(key, null, new ThumbDataContainer(info), false);
            await(view);
            assertPixels(view, Color.BLUE);
            assertTrue("The shared cache request must still be blocked", conaco.isLoading(remote));
            assertEquals(0, requests.get());
        } finally {
            remote.unload();
            writer.close();
            writer.release();
        }
    }

    @Config(sdk = 27)
    @Test public void memoryFallbackRepairsLocalFileAndOfflineRepairFailureKeepsVisibleImage() throws Exception {
        DownloadInfo info = gallery(32, "broken".getBytes(StandardCharsets.UTF_8));
        String key = DownloadCover.getCacheKey(info.gid);
        conaco.getBeerBelly().putToMemory(key, solid(Color.RED));
        view.load(key, "https://cover.invalid/green.png", new ThumbDataContainer(info), true);
        await(view);
        assertPixels(view, Color.GREEN);
        DownloadCover.validate(controller.get(), new File(temporary.getRoot(), "gallery-32/.thumb"));
        assertEquals(1, requests.get());
        conaco.getBeerBelly().clear();
        view.load(DownloadCover.getCacheKey(info.gid), null, new ThumbDataContainer(info), false);
        await(view);
        assertPixels(view, Color.GREEN);
        assertEquals(1, requests.get());

        DownloadInfo offline = gallery(33, "broken".getBytes(StandardCharsets.UTF_8));
        key = DownloadCover.getCacheKey(offline.gid);
        conaco.getBeerBelly().putToMemory(key, solid(Color.RED));
        view.load(key, "https://cover.invalid/offline", new ThumbDataContainer(offline), true);
        await(view);
        assertPixels(view, Color.RED);
        assertEquals(2, requests.get());
    }

    private DownloadInfo gallery(long gid, byte[] bytes) throws Exception {
        File directory = temporary.newFolder("gallery-" + gid);
        if (bytes != null) Files.write(new File(directory, ".thumb").toPath(), bytes);
        DownloadDirectories.directories.put(gid, UniFile.fromFile(directory));
        return new DownloadInfo(gid);
    }

    private void await(LoadImageView target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (!conaco.isLoading(target)) return;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        fail("Image request did not complete");
    }

    private static void assertPixels(LoadImageView target, int expected) {
        assertFalse("Image load failed", target.mFailed);
        Drawable drawable = target.getDrawable();
        assertNotNull("The bound item must have a drawable", drawable);
        if (drawable instanceof TransitionDrawable) {
            TransitionDrawable transition = (TransitionDrawable) drawable;
            drawable = transition.getDrawable(transition.getNumberOfLayers() - 1);
        }
        Bitmap rendered = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, 8, 8);
            drawable.draw(new Canvas(rendered));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) assertEquals("Wrong/recycled/stale cover pixel", expected, rendered.getPixel(x, y));
            }
        } finally {
            rendered.recycle();
        }
    }

    private static Image solid(int color) {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return Image.create(bitmap);
    }

    private static byte[] png(int color) {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return bytes.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    /** Observe every actual delivery, including an obsolete image that might otherwise only flash. */
    private static class CheckedImageView extends LoadImageView {
        int expectedColor;
        CheckedImageView(Context context) { super(context); }
        @Override public boolean onGetValue(Image value, int source) {
            boolean accepted = super.onGetValue(value, source);
            if (accepted && expectedColor != 0) assertPixels(this, expectedColor);
            return accepted;
        }
    }

    /** Keep the real application type/context without starting unrelated database/native services. */
    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {}
    }

    @Implements(EhApplication.class)
    public static class ImageLoader extends org.robolectric.shadows.ShadowApplication {
        static Conaco<Image> conaco;
        @Implementation public static Conaco<Image> getConaco(Context context) { return conaco; }
        @Implementation public static EhApplication getInstance() {
            return (EhApplication) RuntimeEnvironment.getApplication();
        }
    }

    @Implements(SpiderDen.class)
    public static class DownloadDirectories {
        static final Map<Long, UniFile> directories = new ConcurrentHashMap<>();
        static volatile long blockedGid = -1;
        static CountDownLatch entered;
        static CountDownLatch release;
        @Implementation public static UniFile getGalleryDownloadDir(GalleryInfo info) {
            if (info.gid == blockedGid) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Local read was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return directories.get(info.gid);
        }
    }
}
