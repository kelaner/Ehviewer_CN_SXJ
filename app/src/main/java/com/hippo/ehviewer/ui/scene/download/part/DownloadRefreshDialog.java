package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadCover;
import com.hippo.ehviewer.download.DownloadTags;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** One serial, cancellable operation with progress tied to the hosting scene's view. */
public final class DownloadRefreshDialog {
    public enum Mode { TAGS, COVERS, LOCAL_COVER }
    private final Context context;
    private final List<DownloadInfo> items;
    private final Mode mode;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AlertDialog dialog;
    private EhRequest request;
    private volatile Call call;
    private volatile boolean stopped;
    private int completed;
    private int succeeded;
    private int failed;
    private String lastFailure;

    public DownloadRefreshDialog(Context context, List<DownloadInfo> items, Mode mode, Runnable changed) {
        this.context = context.getApplicationContext();
        this.items = new ArrayList<>(items);
        this.mode = mode;
        this.changed = changed;
        int title = mode == Mode.TAGS ? R.string.download_refresh_tags
                : mode == Mode.COVERS ? R.string.download_refresh_covers : R.string.download_reload_cover;
        dialog = new AlertDialog.Builder(context).setTitle(title).setMessage("")
                .setNegativeButton(android.R.string.cancel, (d, which) -> stop()).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> stop());
    }

    public void start() {
        dialog.show();
        update("");
        next();
    }

    private void update(String message) {
        dialog.setMessage(context.getString(R.string.download_refresh_progress,
                items.size(), completed, succeeded, failed, message));
    }

    private void next() {
        if (stopped) return;
        if (completed == items.size()) {
            String summary = context.getString(items.isEmpty() ? R.string.download_refresh_no_items : R.string.download_refresh_finished);
            update(lastFailure == null ? summary : summary + "\n" + lastFailure);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(android.R.string.ok);
            worker.shutdown();
            return;
        }
        DownloadInfo info = items.get(completed);
        update(info.title == null ? Long.toString(info.gid) : info.title);
        if (mode == Mode.LOCAL_COVER) {
            worker.execute(() -> reloadLocal(info));
            return;
        }
        if (TextUtils.isEmpty(info.token) || !TextUtils.isEmpty(info.archiveUri)) {
            finishItem(new IOException(context.getString(R.string.download_refresh_no_token)));
            return;
        }
        request = new EhRequest().setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
                .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token))
                .setCallback(new EhClient.Callback<GalleryDetail>() {
                    @Override public void onSuccess(GalleryDetail detail) {
                        request = null;
                        if (stopped) return;
                        if (mode == Mode.TAGS) {
                            DownloadTags.cacheRawTags(detail);
                            finishItem(null);
                        } else worker.execute(() -> downloadCover(info, detail.thumb));
                    }
                    @Override public void onFailure(Exception error) { request = null; finishItem(error); }
                    @Override public void onCancel() { request = null; }
                });
        EhApplication.getEhClient(context).execute(request);
    }

    private void downloadCover(DownloadInfo info, String url) {
        File temporary = null;
        try {
            if (TextUtils.isEmpty(url)) throw new IOException(context.getString(R.string.download_refresh_invalid_image));
            temporary = File.createTempFile("refresh-cover-", ".image", context.getCacheDir());
            Call downloading = EhApplication.getOkHttpClient(context).newCall(new Request.Builder().url(url)
                    .header("Cache-Control", "no-cache").build());
            call = downloading;
            if (stopped) { downloading.cancel(); return; }
            try (Response response = downloading.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                try (InputStream input = response.body().byteStream(); OutputStream output = new FileOutputStream(temporary)) {
                    copy(input, output);
                }
            }
            if (stopped) return;
            DownloadCover.replace(context, info, temporary);
            main.post(() -> finishItem(null));
        } catch (Exception error) {
            main.post(() -> finishItem(error));
        } finally {
            call = null;
            if (temporary != null) temporary.delete();
        }
    }

    private void reloadLocal(DownloadInfo info) {
        File temporary = null;
        try {
            UniFile directory = SpiderDen.getGalleryDownloadDir(info);
            UniFile cover = directory == null ? null : directory.findFile(".thumb");
            temporary = File.createTempFile("reload-cover-", ".image", context.getCacheDir());
            boolean valid = false;
            if (cover != null) {
                try {
                    copyToFile(cover, temporary);
                    DownloadCover.validate(context, temporary);
                    valid = true;
                } catch (IOException ignored) { }
            }
            if (!valid) {
                UniFile first = directory == null ? null : SpiderDen.findImageFile(directory, 0);
                if (first == null) throw new IOException(context.getString(R.string.download_refresh_invalid_image));
                copyToFile(first, temporary);
            }
            if (stopped) return;
            DownloadCover.replace(context, info, temporary);
            main.post(() -> finishItem(null));
        } catch (Exception error) {
            main.post(() -> finishItem(error));
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private static void copyToFile(UniFile source, File target) throws IOException {
        try (InputStream input = source.openInputStream(); OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private void finishItem(Exception error) {
        if (stopped) return;
        completed++;
        if (error == null) {
            succeeded++;
            changed.run();
        } else {
            failed++;
            DownloadInfo info = items.get(completed - 1);
            lastFailure = (info.title == null ? Long.toString(info.gid) : info.title) + ": " + error.getLocalizedMessage();
        }
        update(error == null ? "" : lastFailure);
        // Post instead of recursing through a library containing only local imports.
        main.post(this::next);
    }

    public void stop() {
        stopped = true;
        if (request != null) request.cancel();
        Call downloading = call;
        if (downloading != null) downloading.cancel();
        worker.shutdownNow();
        dialog.dismiss();
    }
}
