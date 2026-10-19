package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadTags;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Current download-page conditions. Always projects a fresh manager snapshot. */
public final class DownloadListProjection {
    private int state = -1;
    private int category = EhUtils.ALL_CATEGORY;
    private boolean localOnly;
    private int sort = R.id.sort_by_default;

    public void select(int id) {
        if (id == R.id.all) state = -1;
        else if (id == R.id.download_done) state = DownloadInfo.STATE_FINISH;
        else if (id == R.id.not_started) state = DownloadInfo.STATE_NONE;
        else if (id == R.id.waiting) state = DownloadInfo.STATE_WAIT;
        else if (id == R.id.downloading) state = DownloadInfo.STATE_DOWNLOAD;
        else if (id == R.id.failed) state = DownloadInfo.STATE_FAILED;
        else if (id == R.id.all_kind || id == R.id.local_import || id == R.id.unknown
                || id == R.id.misc || id == R.id.doujinshi || id == R.id.manga
                || id == R.id.artist_cg || id == R.id.game_cg || id == R.id.image_set
                || id == R.id.cosplay || id == R.id.asian_porn || id == R.id.non_h
                || id == R.id.western) {
            localOnly = id == R.id.local_import;
            category = categoryFor(id);
        } else sort = id;
    }

    public boolean hasStateFilter() { return state != -1; }

    public void setCategory(int category) {
        this.category = category;
        localOnly = false;
    }

    public List<DownloadInfo> apply(Context context, List<DownloadInfo> source, String query) {
        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : source) {
            if (state != -1 && info.state != state) continue;
            if (category != EhUtils.ALL_CATEGORY && info.category != category) continue;
            if (localOnly && (info.archiveUri == null || info.archiveUri.isEmpty())) continue;
            if (query != null && !query.isEmpty() && !EhUtils.judgeSuitableTitle(info, query)
                    && !matchesTags(context, info, query)) continue;
            result.add(info);
        }
        Collections.sort(result, (left, right) -> {
            if (sort == R.id.sort_by_default) return Long.compare(right.time, left.time);
            int compared = 0;
            if (sort == R.id.sort_by_gallery_id_asc || sort == R.id.sort_by_gallery_id_desc)
                compared = Long.compare(left.gid, right.gid);
            else if (sort == R.id.sort_by_create_time_asc || sort == R.id.sort_by_create_time_desc)
                compared = Long.compare(left.time, right.time);
            else if (sort == R.id.sort_by_rating_asc || sort == R.id.sort_by_rating_desc)
                compared = Float.compare(left.rating, right.rating);
            else if (sort == R.id.sort_by_name_asc || sort == R.id.sort_by_name_desc) {
                if (left.title == null) return right.title == null ? 0 : 1;
                if (right.title == null) return -1;
                compared = left.title.compareToIgnoreCase(right.title);
            } else if (sort == R.id.sort_by_file_size_asc || sort == R.id.sort_by_file_size_desc) {
                if (left.fileSize < 0) return right.fileSize < 0 ? 0 : 1;
                if (right.fileSize < 0) return -1;
                compared = Long.compare(left.fileSize, right.fileSize);
            }
            boolean descending = sort == R.id.sort_by_gallery_id_desc
                    || sort == R.id.sort_by_create_time_desc || sort == R.id.sort_by_rating_desc
                    || sort == R.id.sort_by_name_desc || sort == R.id.sort_by_file_size_desc;
            return descending ? -compared : compared;
        });
        return result;
    }

    private static boolean matchesTags(Context context, DownloadInfo info, String query) {
        if (DownloadTags.matches(context, info.gid, query)) return true;
        List<String> tags = new ArrayList<>();
        if (info.tgList != null) tags.addAll(info.tgList);
        if (info.simpleTags != null) for (String tag : info.simpleTags) tags.add(tag);
        for (String part : query.trim().toLowerCase(java.util.Locale.ROOT).split("\\s{2,}")) {
            boolean found = DownloadTags.matches(context, info.gid, part);
            for (String tag : tags) if (tag != null && tag.toLowerCase(java.util.Locale.ROOT).contains(part)) {
                found = true;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    public static void loadFileSizes(List<DownloadInfo> source) {
        for (DownloadInfo info : source) {
            if (info.fileSize >= 0) continue;
            try {
                UniFile directory = SpiderDen.getGalleryDownloadDir(info);
                if (directory != null && directory.isDirectory()) info.fileSize = folderSize(directory);
            } catch (Exception ignored) {
                // Unavailable storage keeps the existing unknown-size value.
            }
        }
    }

    private static long folderSize(UniFile folder) {
        long size = 0;
        UniFile[] files = folder.listFiles();
        if (files != null) for (UniFile file : files) {
            if (file.isDirectory()) size += folderSize(file);
            else if (file.isFile()) size += Math.max(0, file.length());
        }
        return size;
    }

    private static int categoryFor(int id) {
        if (id == R.id.doujinshi) return EhConfig.DOUJINSHI;
        if (id == R.id.manga) return EhConfig.MANGA;
        if (id == R.id.artist_cg) return EhConfig.ARTIST_CG;
        if (id == R.id.game_cg) return EhConfig.GAME_CG;
        if (id == R.id.image_set) return EhConfig.IMAGE_SET;
        if (id == R.id.cosplay) return EhConfig.COSPLAY;
        if (id == R.id.asian_porn) return EhConfig.ASIAN_PORN;
        if (id == R.id.non_h) return EhConfig.NON_H;
        if (id == R.id.western) return EhConfig.WESTERN;
        if (id == R.id.misc) return EhConfig.MISC;
        if (id == R.id.unknown) return EhUtils.UNKNOWN;
        return EhUtils.ALL_CATEGORY;
    }
}
