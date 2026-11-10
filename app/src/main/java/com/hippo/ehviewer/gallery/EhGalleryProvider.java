/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.gallery;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.SimpleHandler;
import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.hippo.ehviewer.upscale.UpscaleStore;

public class EhGalleryProvider extends GalleryProvider2 implements SpiderQueen.OnSpiderListener {

    private final Context mContext;
    private final GalleryInfo mGalleryInfo;
    private final ExecutorService mUpscaleReader = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> mPageVersions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> mDisplayedUpscaled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Nullable private VariantListener mVariantListener;
    public interface VariantListener { void onDisplayedVariantChanged(int index, boolean upscaled); }
    // An original callback is accepted only after the current variant request chose it.
    private final java.util.HashMap<Integer, Integer> mOriginalRequests = new java.util.HashMap<>();
    private volatile boolean mStopped;
    @Nullable
    private SpiderQueen mSpiderQueen;

    public EhGalleryProvider(Context context, GalleryInfo galleryInfo) {
        mContext = context;
        mGalleryInfo = galleryInfo;
    }

    @Override
    public void start() {
        super.start();

        mSpiderQueen = SpiderQueen.obtainSpiderQueen(mContext, mGalleryInfo, SpiderQueen.MODE_READ);
        mSpiderQueen.addOnSpiderListener(this);
    }

    @Override
    public void stop() {
        mStopped = true;
        mVariantListener = null;
        mDisplayedUpscaled.clear();
        mUpscaleReader.shutdownNow();
        super.stop();

        if (mSpiderQueen != null) {
            mSpiderQueen.removeOnSpiderListener(this);
            // Activity recreate may called, so wait 3000s
            SimpleHandler.getInstance().postDelayed(new ReleaseTask(mSpiderQueen), 3000);
            mSpiderQueen = null;
        }
    }

    @Override
    public int getStartPage() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.getStartPage();
        } else {
            return super.getStartPage();
        }
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        return String.format(Locale.US, "%d-%s-%08d", mGalleryInfo.gid, mGalleryInfo.token, index + 1);
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(index, file);
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(index, dir, filename);
        } else {
            return null;
        }
    }

    @Override
    public void putStartPage(int page) {
        if (mSpiderQueen != null) {
            mSpiderQueen.putStartPage(page);
        }
    }

    @Override
    public int size() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.size();
        } else {
            return GalleryProvider.STATE_ERROR;
        }
    }

    @Override
    protected void onRequest(int index) {
        int version = mPageVersions.getOrDefault(index, 0);
        try {
            mUpscaleReader.execute(() -> {
            if (!isCurrent(index, version)) return;
            File file = getUpscaledFile(index);
            if (file != null && displayUpscaled(index, version, file)) return;
            SpiderQueen queen = mSpiderQueen;
            synchronized (mPageVersions) {
                if (queen == null || !isCurrent(index, version)) return;
                mOriginalRequests.put(index, version);
            }
            if (isCurrent(index, version)) {
                Object object = queen.request(index);
                if (object instanceof Float) notifyPagePercent(index, (Float) object);
                else if (object instanceof String) notifyPageFailed(index, (String) object);
                else if (object == null) notifyPageWait(index);
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The reader has already closed.
        }
    }

    private File getUpscaledFile(int index) {
        if (!EhApplication.getDownloadManager(mContext).containDownloadInfo(mGalleryInfo.gid)) return null;
        return UpscaleStore.getDisplayFile(mContext, mGalleryInfo, index);
    }

    private boolean isCurrent(int index, int version) {
        return !mStopped && mPageVersions.getOrDefault(index, 0) == version;
    }

    public void setVariantListener(@Nullable VariantListener listener) { mVariantListener = listener; }
    public boolean isDisplayingUpscaled(int index) { return mDisplayedUpscaled.contains(index); }
    private void markDisplayed(int index, boolean upscaled) {
        if (upscaled) mDisplayedUpscaled.add(index); else mDisplayedUpscaled.remove(index);
        VariantListener listener = mVariantListener;
        if (listener != null) SimpleHandler.getInstance().post(() -> listener.onDisplayedVariantChanged(index, upscaled));
    }

    private boolean displayUpscaled(int index, int version, File file) {
        Image image;
        try (FileInputStream stream = new FileInputStream(file)) {
            image = Image.decode(stream, false);
        } catch (Exception e) {
            return false;
        }
        if (image == null) return false;
        synchronized (mPageVersions) {
            if (isCurrent(index, version)) { markDisplayed(index, true); notifyPageSucceed(index, image); }
            else image.recycle();
        }
        return true;
    }

    /** Refresh the chosen variant without redownloading or deleting the original. */
    public void refreshVariant(int index) {
        synchronized (mPageVersions) {
            mPageVersions.merge(index, 1, Integer::sum);
            mOriginalRequests.remove(index);
            markDisplayed(index, false);
            removeCache(index);
            notifyDataChanged(index);
        }
    }

    @Override
    protected void onForceRequest(int index) {
        synchronized (mPageVersions) {
            mOriginalRequests.put(index, mPageVersions.getOrDefault(index, 0));
        }
        if (mSpiderQueen != null) {
            Object object = mSpiderQueen.forceRequest(index);
            if (object instanceof Float) {
                notifyPagePercent(index, (Float) object);
            } else if (object instanceof String) {
                notifyPageFailed(index, (String) object);
            } else if (object == null) {
                notifyPageWait(index);
            }
        }
    }

    @Override
    protected void onCancelRequest(int index) {
        if (mSpiderQueen != null) {
            mSpiderQueen.cancelRequest(index);
        }
    }

    @Override
    public String getError() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.getError();
        } else {
            return "Error"; // TODO
        }
    }

    @Override
    public void onGetPages(int pages) {
        notifyDataChanged();
    }

    @Override
    public void onGet509(int index) {
        // TODO
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        synchronized (mPageVersions) {
            Integer requested = mOriginalRequests.get(index);
            if (requested != null && isCurrent(index, requested) && contentLength > 0) {
                notifyPagePercent(index, (float) receivedSize / contentLength);
            }
        }
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        notifyDataChanged(index);
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        notifyOriginalFailure(index, error);
    }

    private void notifyOriginalFailure(int index, String error) {
        synchronized (mPageVersions) {
            Integer requested = mOriginalRequests.get(index);
            if (requested != null && isCurrent(index, requested)) notifyPageFailed(index, error);
        }
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        // SpiderQueen shares this image with its listeners. Do not retain or recycle
        // it when the current request chose the enhanced variant.
        synchronized (mPageVersions) {
            Integer requested = mOriginalRequests.get(index);
            if (requested != null && isCurrent(index, requested)) {
                markDisplayed(index, false);
                notifyPageSucceed(index, image);
            }
        }
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        notifyOriginalFailure(index, error);
    }

    private static class ReleaseTask implements Runnable {

        private SpiderQueen mSpiderQueen;

        public ReleaseTask(SpiderQueen spiderQueen) {
            mSpiderQueen = spiderQueen;
        }

        @Override
        public void run() {
            if (null != mSpiderQueen) {
                SpiderQueen.releaseSpiderQueen(mSpiderQueen, SpiderQueen.MODE_READ);
                mSpiderQueen = null;
            }
        }
    }
}
