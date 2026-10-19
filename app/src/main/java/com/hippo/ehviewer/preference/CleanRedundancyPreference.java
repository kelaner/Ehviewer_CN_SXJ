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

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import java.util.HashSet;
import java.util.Set;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.unifile.UniFile;

public class CleanRedundancyPreference extends TaskPreference {

    public CleanRedundancyPreference(Context context) {
        super(context);
    }

    public CleanRedundancyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CleanRedundancyPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @NonNull
    @Override
    protected Task onCreateTask() {
        return new ClearTask(getContext());
    }

    private static class ClearTask extends Task {

        private final EhApplication mApplication;
        private final DownloadManager mManager;
        private final Set<String> mKnownDirnames = new HashSet<>();

        public ClearTask(@NonNull Context context) {
            super(context);
            mApplication = (EhApplication) context.getApplicationContext();
            mManager = EhApplication.getDownloadManager(mApplication);
            for (DownloadInfo info : mManager.getAllDownloadInfoList()) {
                String dirname = EhDB.getDownloadDirname(info.gid);
                if (dirname != null) mKnownDirnames.add(dirname);
            }
        }

        // True for cleared
        private boolean clearFile(UniFile file) {
            String name = file.getName();
            if (!file.isDirectory() || !file.canRead() || !file.canWrite() || name == null || mKnownDirnames.contains(name)) {
                return false;
            }
            // Folder names may be customized or belong to unrelated user files.
            SpiderInfo header = SpiderInfo.readHeader(file.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
            if (header == null || mManager.containDownloadInfo(header.gid)) {
                return false;
            }
            return file.delete();
        }

        @Override
        protected Object doInBackground(Void... params) {
            UniFile dir = Settings.getDownloadLocation();
            if (null == dir) {
                publishProgress(new int[]{0, 0});
                return 0;
            }
            UniFile[] files = dir.listFiles();
            if (null == files) {
                publishProgress(new int[]{0, 0});
                return 0;
            }

            int total = files.length;
            int count = 0;
            for (int i = 0; i < total; i++) {
                UniFile f = files[i];
                if (clearFile(f)) {
                    ++count;
                }
                publishProgress(new int[]{i + 1, total});
            }

            return count;
        }

        @Override
        protected void onPostExecute(Object o) {
            int count;
            if (o instanceof Integer) {
                count = (Integer) o;
            } else {
                count = 0;
            }

            Toast.makeText(mApplication, 0 == count ?
                    mApplication.getString(R.string.settings_download_clean_redundancy_no_redundancy):
                    mApplication.getString(R.string.settings_download_clean_redundancy_done, count), Toast.LENGTH_SHORT).show();
            super.onPostExecute(o);
        }
    }
}
