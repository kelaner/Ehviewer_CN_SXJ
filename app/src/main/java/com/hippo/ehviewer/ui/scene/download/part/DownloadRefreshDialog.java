package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadCover;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded parallel refresh with per-item results and cancellation tied to the scene. */
public final class DownloadRefreshDialog {
    public enum Mode { METADATA, LOCAL_COVER }
    private static final int PARALLELISM = 3;
    private final Context context;
    private final List<DownloadInfo> items = new ArrayList<>();
    private final Mode mode;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(PARALLELISM);
    private final AlertDialog dialog;
    private final AppCompatCheckBox keepScreenOn;
    private final Map<Long, EhRequest> requests = new HashMap<>();
    private final Set<Call> calls = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private volatile boolean stopped;
    private boolean finished;
    private volatile boolean committed;
    private int scheduled;
    private int active;
    private int completed;
    private int succeeded;
    private int failed;
    private String lastFailure;

    public DownloadRefreshDialog(Context context, List<DownloadInfo> items, Mode mode, Runnable changed) {
        this.context = context.getApplicationContext();
        Set<Long> seen = new HashSet<>();
        for (DownloadInfo info : items) if (seen.add(info.gid)) this.items.add(info);
        this.mode = mode;
        this.changed = changed;
        keepScreenOn = new AppCompatCheckBox(context);
        keepScreenOn.setText(R.string.settings_read_keep_screen_on);
        keepScreenOn.setChecked(false);
        keepScreenOn.setOnCheckedChangeListener((button, checked) -> updateScreenOn());
        int title = mode == Mode.METADATA ? R.string.download_refresh_metadata : R.string.download_reload_cover;
        dialog = new AlertDialog.Builder(context).setTitle(title).setMessage("")
                .setView(keepScreenOn)
                .setNegativeButton(android.R.string.cancel, (d, which) -> stop()).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> stop());
    }

    public void start() {
        dialog.show();
        updateScreenOn();
        update("");
        next();
    }

    private void updateScreenOn() {
        Window window = dialog.getWindow();
        if (window == null) return;
        if (!stopped && !finished && keepScreenOn.isChecked()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void update(String message) {
        dialog.setMessage(context.getString(R.string.download_refresh_progress,
                items.size(), completed, succeeded, failed, message));
    }

    private void next() {
        if (stopped) return;
        if (completed == items.size()) {
            finished = true;
            updateScreenOn();
            keepScreenOn.setEnabled(false);
            String summary = context.getString(items.isEmpty() ? R.string.download_refresh_no_items : R.string.download_refresh_finished);
            update(lastFailure == null ? summary : summary + "\n" + lastFailure);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(android.R.string.ok);
            worker.shutdown();
            return;
        }
        while (active < PARALLELISM && scheduled < items.size()) {
            DownloadInfo info = items.get(scheduled++);
            active++;
            update(info.title == null ? Long.toString(info.gid) : info.title);
            if (mode == Mode.LOCAL_COVER) {
                worker.execute(() -> reloadLocal(info));
            } else if (TextUtils.isEmpty(info.token) || !TextUtils.isEmpty(info.archiveUri)) {
                main.post(() -> finishItem(info, new IOException(context.getString(R.string.download_refresh_no_token))));
            } else {
                requestDetail(info);
            }
        }
    }

    private void requestDetail(DownloadInfo info) {
        EhRequest request = new EhRequest().setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
                .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token), false)
                .setCallback(new EhClient.Callback<GalleryDetail>() {
                    @Override public void onSuccess(GalleryDetail detail) {
                        requests.remove(info.gid);
                        if (stopped) return;
                        worker.execute(() -> downloadCover(info, detail));
                    }
                    @Override public void onFailure(Exception error) {
                        requests.remove(info.gid);
                        finishItem(info, error);
                    }
                    @Override public void onCancel() {
                        requests.remove(info.gid);
                        if (!stopped) finishItem(info, new IOException(context.getString(android.R.string.cancel)));
                    }
                });
        requests.put(info.gid, request);
        EhApplication.getEhClient(context).execute(request);
    }

    private void downloadCover(DownloadInfo info, GalleryDetail detail) {
        File temporary = null;
        Call downloading = null;
        try {
            if (detail.gid != info.gid || TextUtils.isEmpty(detail.title) || detail.pages <= 0
                    || TextUtils.isEmpty(detail.thumb)) {
                throw new IOException(context.getString(R.string.download_refresh_invalid_metadata));
            }
            temporary = File.createTempFile("refresh-cover-", ".image", context.getCacheDir());
            downloading = EhApplication.getOkHttpClient(context).newCall(new Request.Builder().url(detail.thumb)
                    .header("Cache-Control", "no-cache").build());
            calls.add(downloading);
            if (stopped) { downloading.cancel(); return; }
            try (Response response = downloading.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                try (InputStream input = response.body().byteStream(); OutputStream output = new FileOutputStream(temporary)) {
                    copy(input, output);
                }
            }
            DownloadCover.validate(context, temporary);
            synchronized (this) {
                if (stopped) return;
                DownloadCover.replace(context, info, temporary, () -> EhDB.updateDownloadMetadata(info, detail));
                committed = true;
            }
            main.post(() -> {
                // A cancellation may follow a completed commit but precede this UI delivery.
                EhApplication.getGalleryDetailCache(context).put(detail.gid, detail);
                if (stopped) {
                    changed.run();
                    return;
                }
                finishItem(info, null);
            });
        } catch (Exception error) {
            main.post(() -> finishItem(info, error));
        } finally {
            if (downloading != null) calls.remove(downloading);
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
            synchronized (this) {
                if (stopped) return;
                DownloadCover.replace(context, info, temporary);
                committed = true;
            }
            main.post(() -> {
                if (stopped) changed.run();
                else finishItem(info, null);
            });
        } catch (Exception error) {
            main.post(() -> finishItem(info, error));
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
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            output.write(buffer, 0, count);
        }
    }

    private void finishItem(DownloadInfo info, Exception error) {
        if (stopped) return;
        completed++;
        active--;
        if (error == null) {
            succeeded++;
            changed.run();
        } else {
            failed++;
            lastFailure = (info.title == null ? Long.toString(info.gid) : info.title) + ": " + error.getLocalizedMessage();
        }
        update(error == null ? "" : lastFailure);
        main.post(this::next);
    }

    public void stop() {
        // Called on the main thread: never wait for a worker holding the commit monitor
        // across SAF I/O. A commit already in progress is allowed to finish atomically.
        if (stopped) return;
        stopped = true;
        // A commit may have completed before its queued UI callback was delivered.
        if (committed && !finished) changed.run();
        updateScreenOn();
        for (EhRequest request : new ArrayList<>(requests.values())) request.cancel();
        requests.clear();
        for (Call downloading : calls) downloading.cancel();
        worker.shutdownNow();
        dialog.dismiss();
    }
}
