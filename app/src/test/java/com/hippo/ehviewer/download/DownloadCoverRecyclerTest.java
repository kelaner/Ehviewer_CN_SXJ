package com.hippo.ehviewer.download;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Offline download covers must follow the current item through real RecyclerView reuse. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = DownloadCoverRecyclerTest.TestApplication.class,
        shadows = {DownloadCoverRecyclerTest.ImageLoader.class, DownloadCoverRecyclerTest.DownloadDirectories.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadCoverRecyclerTest {
    private static final int IMAGE_SIZE = 8;
    private static final int ROW_HEIGHT = 16;
    private static final int VIEWPORT = 128;
    private static final long FIRST_GID = 10000;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ActivityController<Activity> controller;
    private FrameLayout root;
    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private CoverAdapter adapter;
    private Conaco<Image> conaco;
    private AtomicInteger requests;
    private int nextIdentity;

    @Before public void setUp() throws Exception {
        Image.initialize((EhApplication) RuntimeEnvironment.getApplication());
        DownloadDirectories.directories.clear();
        requests = new AtomicInteger();
        Conaco.Builder<Image> builder = new Conaco.Builder<>();
        builder.hasMemoryCache = true;
        builder.memoryCacheMaxSize = IMAGE_SIZE * IMAGE_SIZE * 4 * 4;
        builder.hasDiskCache = true;
        builder.diskCacheDir = temporary.newFolder("shared-cache");
        builder.diskCacheMaxSize = 1024 * 1024;
        builder.objectHelper = new ImageBitmapHelper();
        builder.okHttpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Offline RecyclerView acceptance test");
        }).build();
        conaco = builder.build();
        ImageLoader.conaco = conaco;

        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        root = new FrameLayout(controller.get());
        controller.get().setContentView(root);
        recycler = new RecyclerView(controller.get());
        layoutManager = new LinearLayoutManager(controller.get());
        recycler.setLayoutManager(layoutManager);
        recycler.setItemAnimator(null);
        recycler.setItemViewCacheSize(0);
        recycler.getRecycledViewPool().setMaxRecycledViews(0, 12);
        adapter = new CoverAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new FrameLayout.LayoutParams(VIEWPORT, VIEWPORT));
        layoutNow();
    }

    @After public void tearDown() {
        if (recycler != null) {
            for (int i = 0; i < recycler.getChildCount(); i++) {
                ((CoverHolder) recycler.getChildViewHolder(recycler.getChildAt(i))).view.unload();
            }
            recycler.setAdapter(null);
        }
        if (controller != null) controller.pause().stop().destroy();
        if (conaco != null) conaco.getBeerBelly().clearMemory();
        ImageLoader.conaco = null;
        DownloadDirectories.directories.clear();
    }

    @Test public void offlineCoversSurviveBatchInsertionAndRapidHolderReuseBeyond1200Items() throws Exception {
        // Front/middle insertions shift existing positions as well as growing the list.
        insertBatch(0, 79);
        exerciseVisibleRanges();
        insertBatch(0, 263);
        exerciseVisibleRanges();
        insertBatch(adapter.getItemCount() / 2, 401);
        exerciseVisibleRanges();
        insertBatch(adapter.getItemCount(), 458);
        assertEquals(1201, adapter.getItemCount());
        assertEquals(1201, DownloadDirectories.directories.size());

        for (int pass = 0; pass < 3; pass++) exerciseVisibleRanges();

        assertTrue("A real viewport must create multiple holders", adapter.created > 1);
        assertTrue("Scrolling must actually recycle holders", adapter.recycled > 0);
        assertTrue("At least one holder must bind a different gallery", adapter.reused > 0);
        assertEquals("Local .thumb covers with null remote URLs must never use the network",
                0, requests.get());
    }

    private void insertBatch(int position, int count) throws Exception {
        List<DownloadInfo> added = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long gid = FIRST_GID + nextIdentity++;
            File directory = temporary.newFolder("gallery-" + gid);
            Files.write(new File(directory, ".thumb").toPath(), png(gid));
            DownloadDirectories.directories.put(gid, UniFile.fromFile(directory));
            DownloadInfo info = new DownloadInfo(gid);
            info.thumb = null;
            added.add(info);
        }
        adapter.items.addAll(position, added);
        adapter.notifyItemRangeInserted(position, count);
        layoutNow();
    }

    private void exerciseVisibleRanges() throws Exception {
        int last = adapter.getItemCount() - 1;
        int middle = last / 2;
        // Deliberately do not drain the main looper or await images between these jumps.
        // This keeps obsolete loads competing with newly rebound holders.
        for (int target : new int[]{last, 0, middle, last, middle, 0, last, 0}) {
            jumpTo(target);
            recycler.scrollBy(0, target == last ? -ROW_HEIGHT * 3 : ROW_HEIGHT * 3);
            conaco.getBeerBelly().clearMemory();
        }
        for (int target : new int[]{0, middle, last}) {
            jumpTo(target);
            awaitAndAssertVisible(target);
            // Also exercise eviction while loaded drawables are still attached.
            conaco.getBeerBelly().clearMemory();
            assertVisiblePixels(target);
        }
    }

    private void jumpTo(int position) {
        layoutManager.scrollToPositionWithOffset(position, 0);
        layoutNow();
    }

    private void layoutNow() {
        root.forceLayout();
        recycler.forceLayout();
        int exact = View.MeasureSpec.makeMeasureSpec(VIEWPORT, View.MeasureSpec.EXACTLY);
        root.measure(exact, exact);
        root.layout(0, 0, VIEWPORT, VIEWPORT);
        recycler.measure(exact, exact);
        recycler.layout(0, 0, VIEWPORT, VIEWPORT);
    }

    private void awaitAndAssertVisible(int target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            layoutNow();
            boolean loading = false;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                CoverHolder holder = (CoverHolder) recycler.getChildViewHolder(recycler.getChildAt(i));
                if (conaco.isLoading(holder.view)) loading = true;
            }
            if (!loading) {
                assertVisiblePixels(target);
                return;
            }
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        fail("Visible cover requests did not finish at target " + target);
    }

    private void assertVisiblePixels(int target) {
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        assertTrue("The viewport must contain visible holders", first != RecyclerView.NO_POSITION);
        assertTrue("Requested first/middle/last item must be visible: " + target + " actual=" + first + ".." + last + " height=" + recycler.getHeight(),
                first <= target && target <= last);
        int checked = 0;
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (child.getBottom() <= 0 || child.getTop() >= recycler.getHeight()) continue;
            CoverHolder holder = (CoverHolder) recycler.getChildViewHolder(child);
            int position = holder.getBindingAdapterPosition();
            assertTrue("Visible holder must have a current adapter position",
                    position >= 0 && position < adapter.getItemCount());
            assertPixels(holder.view, adapter.items.get(position).gid, position);
            checked++;
        }
        assertTrue("Pixel verification must not pass on an empty viewport", checked > 0);
        assertTrue("The test must verify multiple simultaneous visible holders", checked > 1);
    }

    private static void assertPixels(LoadImageView view, long gid, int position) {
        assertFalse("Cover load failed at position " + position, view.mFailed);
        Drawable drawable = view.getDrawable();
        assertNotNull("Missing cover at position " + position, drawable);
        if (drawable instanceof TransitionDrawable) {
            TransitionDrawable transition = (TransitionDrawable) drawable;
            drawable = transition.getDrawable(transition.getNumberOfLayers() - 1);
        }
        Bitmap rendered = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, IMAGE_SIZE, IMAGE_SIZE);
            drawable.draw(new Canvas(rendered));
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) {
                    assertEquals("Wrong/stale cover at position " + position + " pixel " + x + "," + y,
                            pixel(gid, x, y), rendered.getPixel(x, y));
                }
            }
        } finally {
            rendered.recycle();
        }
    }

    private static int pixel(long gid, int x, int y) {
        // Eleven repeated identity bits distinguish all 1201 galleries, not just odd/even rows.
        int bit = (y * IMAGE_SIZE + x) % 11;
        return (((gid - FIRST_GID + 1) >> bit) & 1) == 0 ? Color.BLUE : Color.GREEN;
    }

    private static byte[] png(long gid) {
        Bitmap bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) bitmap.setPixel(x, y, pixel(gid, x, y));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return bytes.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static class CoverHolder extends RecyclerView.ViewHolder {
        final LoadImageView view;
        long previousGid = -1;
        CoverHolder(LoadImageView view) {
            super(view);
            this.view = view;
        }
    }

    private static class CoverAdapter extends RecyclerView.Adapter<CoverHolder> {
        final List<DownloadInfo> items = new ArrayList<>();
        int created;
        int recycled;
        int reused;

        @NonNull @Override public CoverHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LoadImageView view = new LoadImageView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT));
            created++;
            return new CoverHolder(view);
        }

        @Override public void onBindViewHolder(@NonNull CoverHolder holder, int position) {
            DownloadInfo info = items.get(position);
            if (holder.previousGid != -1 && holder.previousGid != info.gid) reused++;
            holder.previousGid = info.gid;
            LoadImageView view = holder.view;
            view.load(DownloadCover.getCacheKey(info.gid), info.thumb, new ThumbDataContainer(info), true, false);
        }

        @Override public void onViewRecycled(@NonNull CoverHolder holder) {
            holder.view.unload();
            recycled++;
            super.onViewRecycled(holder);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /** Retain the production context type without initializing unrelated application services. */
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
        @Implementation public static UniFile getGalleryDownloadDir(GalleryInfo info) {
            return directories.get(info.gid);
        }
    }
}
