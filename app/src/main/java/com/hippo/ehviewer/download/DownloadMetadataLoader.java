package com.hippo.ehviewer.download;

import android.content.Context;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Fetch missing original tags serially using the application's existing session. */
public final class DownloadMetadataLoader {
    private final Context context;
    private final Runnable onChanged;
    private final ArrayDeque<DownloadInfo> pending = new ArrayDeque<>();
    private final Set<Long> scheduled = new HashSet<>();
    private EhRequest request;
    private boolean stopped;

    public DownloadMetadataLoader(Context context, Runnable onChanged) {
        this.context = context.getApplicationContext();
        this.onChanged = onChanged;
    }

    public void start(List<DownloadInfo> downloads) {
        enqueue(downloads);
    }

    public void enqueue(List<DownloadInfo> downloads) {
        if (stopped) return;
        boolean changed = false;
        for (DownloadInfo info : downloads) {
            if (!scheduled.add(info.gid)) continue;
            if (!DownloadTags.hasRawTags(info.gid)) {
                if (DownloadTags.cacheFromDetailCache(context, info.gid)) {
                    changed = true;
                } else if (info.token != null && !info.token.isEmpty()) {
                    pending.add(info);
                }
            }
        }
        if (changed) onChanged.run();
        if (request == null) next();
    }

    private void next() {
        if (stopped) return;
        DownloadInfo info;
        do {
            info = pending.poll();
            if (info == null) return;
        } while (DownloadTags.hasRawTags(info.gid)
                || !EhApplication.getDownloadManager(context).containDownloadInfo(info.gid));
        request = new EhRequest()
                .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
                .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token))
                .setCallback(new EhClient.Callback<GalleryDetail>() {
                    @Override
                    public void onSuccess(GalleryDetail detail) {
                        request = null;
                        if (stopped) return;
                        // EhEngine persists the parsed original tags before this callback.
                        onChanged.run();
                        next();
                    }

                    @Override
                    public void onFailure(Exception error) {
                        request = null;
                        if (!stopped) next();
                    }

                    @Override
                    public void onCancel() {
                        request = null;
                    }
                });
        EhApplication.getEhClient(context).execute(request);
    }

    public void stop() {
        stopped = true;
        pending.clear();
        if (request != null) {
            request.cancel();
            request = null;
        }
    }
}
