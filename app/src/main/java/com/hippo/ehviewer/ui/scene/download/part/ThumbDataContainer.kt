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
package com.hippo.ehviewer.ui.scene.download.part

import com.hippo.conaco.DataContainer
import com.hippo.conaco.ProgressNotifier
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadCover
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.streampipe.InputStreamPipe
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Download covers are replaced only after a complete, decodable image is available. */
class ThumbDataContainer(private val mInfo: DownloadInfo) : DataContainer {
    override fun isEnabled(): Boolean = SpiderDen.getGalleryDownloadDir(mInfo)?.isDirectory == true

    override fun onUrlMoved(requestUrl: String?, responseUrl: String?) = Unit

    override fun save(
        `is`: InputStream,
        length: Long,
        mediaType: String?,
        notify: ProgressNotifier?
    ): Boolean {
        val context = EhApplication.getInstance()
        var temporary: File? = null
        return try {
            temporary = File.createTempFile("download-cover-", ".image", context.cacheDir)
            temporary.outputStream().use { output -> `is`.copyTo(output) }
            DownloadCover.saveIfMissing(context, mInfo, temporary)
            true
        } catch (error: IOException) {
            false
        } finally {
            temporary?.delete()
        }
    }

    override fun get(): InputStreamPipe? {
        val file = SpiderDen.getGalleryDownloadDir(mInfo)?.findFile(".thumb") ?: return null
        return if (file.length() > 0) UniFileInputStreamPipe(file) else null
    }

    override fun remove() {
        SpiderDen.getGalleryDownloadDir(mInfo)?.findFile(".thumb")?.delete()
    }
}
