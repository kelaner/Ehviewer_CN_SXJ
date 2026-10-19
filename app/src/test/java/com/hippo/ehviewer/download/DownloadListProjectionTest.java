package com.hippo.ehviewer.download;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.scene.download.part.DownloadListProjection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Regression contracts for preserving active conditions across a changed download source. */
public class DownloadListProjectionTest {
    private static DownloadInfo item(long gid, int state, int category) {
        DownloadInfo info = new DownloadInfo(gid);
        info.state = state;
        info.category = category;
        return info;
    }

    private static List<Long> ids(List<DownloadInfo> items) {
        List<Long> result = new ArrayList<>();
        for (DownloadInfo info : items) result.add(info.gid);
        return result;
    }

    @Test
    public void sortingSurvivesDeletionAndIncludesNewSourceItems() {
        DownloadListProjection view = new DownloadListProjection();
        view.select(R.id.sort_by_gallery_id_asc);
        List<DownloadInfo> source = new ArrayList<>(Arrays.asList(
                item(30, DownloadInfo.STATE_FINISH, EhConfig.MANGA),
                item(10, DownloadInfo.STATE_FINISH, EhConfig.MANGA),
                item(20, DownloadInfo.STATE_FINISH, EhConfig.MANGA)));
        assertEquals(Arrays.asList(10L, 20L, 30L), ids(view.apply(null, source, null)));
        source.remove(2);
        source.add(item(5, DownloadInfo.STATE_FINISH, EhConfig.MANGA));
        assertEquals(Arrays.asList(5L, 10L, 30L), ids(view.apply(null, source, null)));
    }

    @Test
    public void filteringSurvivesDeletionAndTracksStateChanges() {
        DownloadListProjection view = new DownloadListProjection();
        view.select(R.id.download_done);
        DownloadInfo first = item(10, DownloadInfo.STATE_FINISH, EhConfig.MANGA);
        DownloadInfo waiting = item(20, DownloadInfo.STATE_WAIT, EhConfig.MANGA);
        DownloadInfo last = item(30, DownloadInfo.STATE_FINISH, EhConfig.MANGA);
        List<DownloadInfo> source = new ArrayList<>(Arrays.asList(first, waiting, last));
        assertEquals(Arrays.asList(10L, 30L), ids(view.apply(null, source, null)));
        source.remove(first);
        assertEquals(Arrays.asList(30L), ids(view.apply(null, source, null)));
        waiting.state = DownloadInfo.STATE_FINISH;
        assertEquals(Arrays.asList(20L, 30L), ids(view.apply(null, source, null)));
    }

    @Test
    public void sortAndBothFiltersComposeInEitherSelectionOrderAfterDeletion() {
        for (boolean sortingFirst : new boolean[]{true, false}) {
            DownloadListProjection view = new DownloadListProjection();
            if (sortingFirst) view.select(R.id.sort_by_gallery_id_desc);
            view.select(R.id.download_done);
            view.select(R.id.manga);
            if (!sortingFirst) view.select(R.id.sort_by_gallery_id_desc);
            DownloadInfo deleted = item(30, DownloadInfo.STATE_FINISH, EhConfig.MANGA);
            List<DownloadInfo> source = new ArrayList<>(Arrays.asList(
                    item(10, DownloadInfo.STATE_FINISH, EhConfig.MANGA),
                    item(50, DownloadInfo.STATE_WAIT, EhConfig.MANGA),
                    item(40, DownloadInfo.STATE_FINISH, EhConfig.DOUJINSHI),
                    deleted,
                    item(20, DownloadInfo.STATE_FINISH, EhConfig.MANGA)));
            assertEquals(Arrays.asList(30L, 20L, 10L), ids(view.apply(null, source, null)));
            source.remove(deleted);
            assertEquals(Arrays.asList(20L, 10L), ids(view.apply(null, source, null)));
            view.select(R.id.all);
            assertEquals(Arrays.asList(50L, 20L, 10L), ids(view.apply(null, source, null)));
        }
    }
}
