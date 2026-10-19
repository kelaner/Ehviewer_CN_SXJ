package com.hippo.ehviewer.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Validate a complete replacement before touching the current download cover. */
public final class DownloadCover {
    private DownloadCover() { }

    /** A delayed thumbnail request must never overwrite a cover explicitly refreshed meanwhile. */
    public static synchronized void saveIfMissing(Context context, DownloadInfo info, File source) throws IOException {
        UniFile directory = SpiderDen.getGalleryDownloadDir(info);
        UniFile current = directory == null ? null : directory.findFile(".thumb");
        if (current != null && current.length() > 0) return;
        replace(context, info, source);
    }

    public static synchronized void replace(Context context, DownloadInfo info, File source) throws IOException {
        replace(context, info, source, () -> { });
    }

    /** Retain the previous cover until the metadata transaction has committed too. */
    public static synchronized void replace(Context context, DownloadInfo info, File source, Runnable commit) throws IOException {
        validate(context, source);
        UniFile directory = SpiderDen.getGalleryDownloadDir(info);
        if (directory == null || !directory.isDirectory()) throw new IOException(context.getString(R.string.download_refresh_storage_error));
        String suffix = UUID.randomUUID().toString();
        UniFile staged = directory.createFile(".thumb-new-" + suffix);
        if (staged == null) throw new IOException(context.getString(R.string.download_refresh_storage_error));
        boolean moved = false;
        try {
            try (InputStream input = new FileInputStream(source); OutputStream output = staged.openOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            UniFile current = directory.findFile(".thumb");
            if (current != null && !current.renameTo(".thumb-backup-" + suffix))
                throw new IOException(context.getString(R.string.download_refresh_storage_error));
            if (!staged.renameTo(".thumb")) {
                if (current != null && !current.renameTo(".thumb"))
                    throw new IOException("Cover replacement failed; original retained as .thumb-backup-" + suffix);
                throw new IOException(context.getString(R.string.download_refresh_storage_error));
            }
            moved = true;
            try {
                EhApplication.getConaco(context).getBeerBelly().remove(EhCacheKeyFactory.getThumbKey(info.gid));
                commit.run();
            } catch (RuntimeException | Error error) {
                UniFile replacement = directory.findFile(".thumb");
                if (replacement == null || !replacement.delete()
                        || (current != null && !current.renameTo(".thumb"))) {
                    throw new IOException("Metadata commit failed; original cover retained as .thumb-backup-" + suffix, error);
                }
                throw error;
            }
            if (current != null) current.delete();
        } finally {
            // After rename, staged points at .thumb, which may already be the restored original.
            if (!moved) staged.delete();
        }
    }

    public static void validate(Context context, File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException(context.getString(R.string.download_refresh_invalid_image));
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException(context.getString(R.string.download_refresh_invalid_image));
        bitmap.recycle();
    }
}
