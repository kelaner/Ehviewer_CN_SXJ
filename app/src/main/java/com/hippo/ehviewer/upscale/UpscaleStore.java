package com.hippo.ehviewer.upscale;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.unifile.UniFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Persistent, separate copies of enhanced pages. All disk operations run off the UI thread. */
public final class UpscaleStore {
    public interface Callback { void onComplete(boolean success, String message); }
    public static final class Entry {
        public long galleryId, originalBytes, upscaledBytes;
        public int pageCount;
        public String title, modelPreset;
    }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<>());
    private static final Object LOCK = new Object();
    private static File root(Context c) { File f = new File(c.getFilesDir(), "upscaled-pages"); f.mkdirs(); return f; }
    private static File folder(Context c, long gid) { return new File(root(c), Long.toString(gid)); }
    private static File metadata(Context c, long gid, int page) { return new File(folder(c, gid), page + ".json"); }
    private static File output(Context c, long gid, int page) { return new File(folder(c, gid), page + ".png"); }
    private static JSONObject read(File file) throws Exception {
        try (InputStream in = new AtomicFile(file).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static void write(File file, JSONObject value) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream out = atomic.startWrite();
        try { out.write(value.toString().getBytes("UTF-8")); atomic.finishWrite(out); }
        catch (Exception e) { atomic.failWrite(out); throw e; }
    }
    private static String hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[65536]; int n;
        try (InputStream in = input) { while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte v : digest.digest()) s.append(String.format(Locale.US, "%02x", v & 255)); return s.toString();
    }
    private static File namedResult(Context c, long gid, String name) throws IOException {
        if (!name.startsWith("result-") || !name.endsWith(".tmp") || !name.equals(new File(name).getName())) throw new IOException("无效的恢复记录");
        return new File(folder(c, gid), name);
    }
    private static File resultFile(Context c, long gid, int page, JSONObject m) throws IOException {
        String name = m.optString("resultFile", "");
        return name.isEmpty() ? output(c, gid, page) : namedResult(c, gid, name);
    }
    private static void recover(Context c, long gid, int page, JSONObject m) throws Exception {
        String name = m.optString("pendingResult", "");
        if (name.isEmpty()) return;
        File temp = namedResult(c, gid, name), target = output(c, gid, page);
        String expected = m.getString("resultHash");
        if (target.exists()) {
            if (!target.isFile() || !expected.equals(hash(new FileInputStream(target)))) throw new IOException("已有结果与恢复记录不符");
            if (temp.exists() && (!temp.isFile() || !expected.equals(hash(new FileInputStream(temp))))) throw new IOException("待恢复结果不匹配");
            if (temp.exists() && !temp.delete()) throw new IOException("无法清理临时结果");
        } else {
            if (!temp.isFile() || !expected.equals(hash(new FileInputStream(temp)))) throw new IOException("待恢复结果不可用");
            m.put("resultFile", name);
        }
        m.remove("pendingResult");
        write(metadata(c, gid, page), m);
    }
    private static JSONObject readPage(Context c, long gid, int page) throws Exception {
        JSONObject m = read(metadata(c, gid, page));
        if (m.has("pendingDelete")) throw new IOException("删除尚未完成，请在管理页重试");
        recover(c, gid, page, m);
        return m;
    }
    private static boolean valid(File f) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getAbsolutePath(), o); return o.outWidth > 0 && o.outHeight > 0; }
    private static boolean fullyDecodable(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            com.hippo.lib.image.Image image = com.hippo.lib.image.Image.decode(in, false);
            if (image == null) return false;
            image.recycle();
            return true;
        } catch (Exception | OutOfMemoryError e) { return false; }
    }
    public static void requestPage(Context context, GalleryInfo gallery, int page, UniFile original, Callback callback) {
        Context c = context.getApplicationContext(); String key = gallery.gid + ":" + page;
        if (!PENDING.add(key)) { MAIN.post(() -> callback.onComplete(false, "该页面正在处理")); return; }
        WORK.execute(() -> {
            boolean ok = false; String message;
            try {
                {
                    if (hasUpscaled(c, gallery.gid, page)) throw new IOException("该页已有超分结果，请先在管理页删除");
                    if (original == null || !original.exists()) throw new IOException("原图不存在");
                    File dir = folder(c, gallery.gid); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建超分目录");
                    File inputFile = File.createTempFile("source-", ".tmp", dir);
                    File result;
                    try { result = File.createTempFile("result-", ".tmp", dir); }
                    catch (Exception e) { inputFile.delete(); throw e; }
                    Bitmap input = null, scaled = null; boolean keepResult = false;
                    try {
                        try (InputStream in = original.openInputStream(); OutputStream out = new FileOutputStream(inputFile)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
                        String sourceHash = hash(new FileInputStream(inputFile));
                        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(inputFile.getPath(), bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 16000000L) throw new IOException("图片无效或尺寸过大");
                        if ("image/gif".equals(bounds.outMimeType)) throw new IOException("暂不支持动画图片");
                        input = BitmapFactory.decodeFile(inputFile.getPath()); if (input == null) throw new IOException("无法解码原图");
                        if (input.getConfig() != Bitmap.Config.ARGB_8888) { Bitmap converted = input.copy(Bitmap.Config.ARGB_8888, false); input.recycle(); input = converted; }
                        try (VulkanUpscaler engine = new VulkanUpscaler(c)) { scaled = engine.upscale(input); }
                        if (scaled == null || scaled.getWidth() != input.getWidth() * 2 || scaled.getHeight() != input.getHeight() * 2) throw new IOException("超分输出尺寸异常");
                        try (FileOutputStream out = new FileOutputStream(result)) { if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("保存失败"); out.getFD().sync(); }
                        if (!fullyDecodable(result)) throw new IOException("超分结果无法完整解码");
                        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gallery.gid, page)) {
                          synchronized (LOCK) {
                            if (hasUpscaled(c, gallery.gid, page)) throw new IOException("该页已有超分结果，请先在管理页删除");
                            if (!sourceHash.equals(hash(original.openInputStream()))) throw new IOException("处理期间原图已更改");
                            File target = output(c, gallery.gid, page), meta = metadata(c, gallery.gid, page);
                            if (target.exists() || meta.exists() || new File(meta.getPath() + ".bak").exists() || new File(meta.getPath() + ".new").exists()) throw new IOException("已有结果或恢复记录，请先检查");
                            JSONObject m = new JSONObject(); m.put("gid", gallery.gid); m.put("title", gallery.title); m.put("page", page); m.put("model", "AnimeVideo v3 2×"); m.put("uri", original.getUri().toString()); m.put("originalHash", sourceHash); m.put("originalBytes", inputFile.length()); m.put("resultHash", hash(new FileInputStream(result))); m.put("showOriginal", false); m.put("pendingResult", result.getName());
                            keepResult = true;
                            try { write(meta, m); }
                            catch (Exception e) { if (!meta.exists() && !new File(meta.getPath() + ".bak").exists() && !new File(meta.getPath() + ".new").exists()) keepResult = false; throw e; }
                            recover(c, gallery.gid, page, m);
                          }
                        }
                        ok = true; message = "超分完成";
                    } finally { if (input != null) input.recycle(); if (scaled != null) scaled.recycle(); inputFile.delete(); if (!keepResult) result.delete(); }
                }
            } catch (Throwable e) { message = "超分失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            PENDING.remove(key); final boolean success = ok; final String text = message; MAIN.post(() -> callback.onComplete(success, text));
        });
    }
    public static boolean hasUpscaled(Context c, long gid, int page) { synchronized (LOCK) { try { JSONObject m = readPage(c,gid,page); return valid(resultFile(c,gid,page,m)); } catch (Exception e) { return false; } } }
    public enum PageStatus { ABSENT, VALID, INVALID }
    /** Call off the UI thread. Unreadable or inconsistent records are INVALID. */
    public static PageStatus getPageStatus(Context c, GalleryInfo gallery, int page) {
        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gallery.gid, page)) {
            synchronized (LOCK) {
                File meta = metadata(c, gallery.gid, page);
                if (!meta.exists() && !new File(meta.getPath() + ".bak").exists() && !new File(meta.getPath() + ".new").exists())
                    return output(c, gallery.gid, page).exists() ? PageStatus.INVALID : PageStatus.ABSENT;
                try {
                    JSONObject m = readPage(c, gallery.gid, page);
                    Uri source = currentSource(c, gallery, page);
                    File result = resultFile(c, gallery.gid, page, m);
                    return (source == null || m.getString("originalHash").equals(hash(c.getContentResolver().openInputStream(source))))
                            && valid(result) && m.getString("resultHash").equals(hash(new FileInputStream(result))) ? PageStatus.VALID : PageStatus.INVALID;
                } catch (Exception e) { return PageStatus.INVALID; }
            }
        }
    }
    /** Call off the UI thread. Null means the directory was read and the page is absent. */
    public static UniFile getCurrentSource(Context c, GalleryInfo gallery, int page) throws Exception {
        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gallery.gid, page)) {
            Uri uri = currentSource(c, gallery, page);
            return uri == null ? null : UniFile.fromUri(c, uri);
        }
    }
    // Null means a successful directory listing found no source; failures throw.
    private static Uri currentSource(Context c, GalleryInfo gallery, int page) throws Exception {
        UniFile dir = com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir(gallery);
        if (dir == null) throw new IOException("无法访问下载目录");
        if ("content".equals(dir.getUri().getScheme())) {
            Uri uri = dir.getUri();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri));
            try (Cursor cursor = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException("无法读取下载目录");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                        if (com.hippo.ehviewer.spider.SpiderDen.generateImageFilename(page, extension).equals(name)) return DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0));
                    }
                }
            }
        } else {
            UniFile[] files = dir.listFiles();
            if (files == null) throw new IOException("无法读取下载目录");
            for (UniFile file : files) for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                if (com.hippo.ehviewer.spider.SpiderDen.generateImageFilename(page, extension).equals(file.getName())) return file.getUri();
            }
        }
        return null;
    }
    /** Call off the UI thread. Acquire the original-page lock before the store lock. */
    public static File getDisplayFile(Context c, GalleryInfo gallery, int page) {
        long gid = gallery.gid;
        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gid, page)) {
            Uri sourceUri = currentSource(c, gallery, page);
            synchronized (LOCK) {
                JSONObject m = readPage(c, gid, page);
                File f = resultFile(c, gid, page, m);
                if (m.optBoolean("showOriginal", false) || !valid(f) || !m.getString("resultHash").equals(hash(new FileInputStream(f)))) return null;
                if (sourceUri != null) return m.getString("originalHash").equals(hash(c.getContentResolver().openInputStream(sourceUri))) ? f : null;
                return f;
            }
        } catch (Exception e) { return null; }
    }
    public static final class DisplayState {
        public final boolean showOriginal, originalAvailable, upscaledAvailable;
        private DisplayState(boolean showOriginal, boolean originalAvailable, boolean upscaledAvailable) {
            this.showOriginal = showOriginal; this.originalAvailable = originalAvailable; this.upscaledAvailable = upscaledAvailable;
        }
    }
    private static DisplayState displayState(Context c, GalleryInfo gallery, int page, JSONObject m) {
        boolean originalAvailable = false, sourceSafe = false, upscaledAvailable = false;
        try {
            Uri source = currentSource(c, gallery, page);
            originalAvailable = source != null && m.getString("originalHash").equals(hash(c.getContentResolver().openInputStream(source)));
            sourceSafe = source == null || originalAvailable;
        } catch (Exception ignored) {}
        try {
            File result = resultFile(c, gallery.gid, page, m);
            upscaledAvailable = sourceSafe && valid(result) && m.getString("resultHash").equals(hash(new FileInputStream(result)));
        } catch (Exception ignored) {}
        return new DisplayState(m.optBoolean("showOriginal", false), originalAvailable, upscaledAvailable);
    }
    /** Query availability off the UI thread. A null state means the record is unavailable. */
    public static DisplayState getDisplayState(Context c, GalleryInfo gallery, int page) {
        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gallery.gid, page)) {
            synchronized (LOCK) {
                try { return displayState(c, gallery, page, readPage(c, gallery.gid, page)); }
                catch (Exception e) { return null; }
            }
        }
    }
    /** Select a variant off the UI thread; null indicates success. */
    public static String setDisplayVariant(Context c, GalleryInfo gallery, int page, boolean showOriginal) {
        try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gallery.gid, page)) {
            synchronized (LOCK) {
                try {
                    JSONObject m = readPage(c, gallery.gid, page);
                    DisplayState state = displayState(c, gallery, page, m);
                    if (showOriginal && !state.originalAvailable) return "原图不存在或已更改";
                    if (!showOriginal && !state.upscaledAvailable) return "超分图不存在或已损坏";
                    m.put("showOriginal", showOriginal); write(metadata(c, gallery.gid, page), m); return null;
                } catch (Exception e) { return "切换失败，请检查图片及存储权限"; }
            }
        }
    }
    private static File[] metadataFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") || n.endsWith(".json.bak"));
        if (files == null) return null;
        Set<File> unique = new HashSet<>();
        for (File f : files) unique.add(f.getName().endsWith(".bak") ? new File(dir, f.getName().substring(0, f.getName().length() - 4)) : f);
        return unique.toArray(new File[0]);
    }
    public static List<Entry> list(Context c) {
        synchronized (LOCK) { List<Entry> entries = new ArrayList<>(); File[] dirs = root(c).listFiles(File::isDirectory); if (dirs == null) return entries;
            for (File dir : dirs) { Entry e = new Entry(); try { e.galleryId = Long.parseLong(dir.getName()); } catch (Exception ignored) { continue; }
                File[] files = metadataFiles(dir); if (files == null) continue;
                for (File file : files) {
                    JSONObject m = null;
                    try { m = read(file); } catch (Exception ignored) {}
                    int page;
                    try { page = Integer.parseInt(file.getName().substring(0, file.getName().length() - 5)); } catch (Exception ignored) { continue; }
                    if (m != null && !m.has("pendingDelete")) try { recover(c, e.galleryId, page, m); } catch (Exception ignored) {}
                    File result;
                    try { result = m != null ? resultFile(c, e.galleryId, page, m) : output(c, e.galleryId, page); }
                    catch (Exception ignored) { result = output(c, e.galleryId, page); }
                    e.title = m != null ? m.optString("title", Long.toString(e.galleryId)) : Long.toString(e.galleryId);
                    e.modelPreset = m != null ? m.optString("model") : "";
                    e.pageCount++; e.originalBytes += m != null ? m.optLong("originalBytes") : 0; e.upscaledBytes += result.length();
                }
                if (e.pageCount > 0) entries.add(e);
            } return entries;
        }
    }
    private static boolean sourceDefinitelyAbsent(Context c, Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) {
            File source = new File(uri.getPath()), parent = source.getParentFile();
            String[] names = parent != null ? parent.list() : null;
            if (names == null) throw new IOException("无法读取原图目录");
            for (String name : names) if (name.equals(source.getName())) return false;
            return true;
        }
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = c.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (cursor == null) throw new IOException("无法查询原图");
                return !cursor.moveToFirst();
            }
        }
        throw new IOException("无法确认原图状态");
    }
    public static String delete(Context c, Collection<Long> gids, boolean original) {
        int done = 0, failed = 0;
        for (long gid : gids) {
            File[] files = metadataFiles(folder(c, gid)); if (files == null) continue;
            for (File file : files) {
                try {
                    int page = Integer.parseInt(file.getName().substring(0, file.getName().length() - 5));
                    try (com.hippo.ehviewer.spider.SpiderDen.OriginalPageGuard guard = com.hippo.ehviewer.spider.SpiderDen.lockOriginalPage(gid, page)) {
                        synchronized (LOCK) {
                            JSONObject m = read(file); File result = resultFile(c, gid, page, m); UniFile source = UniFile.fromUri(c, Uri.parse(m.getString("uri")));
                            String operation = original ? "original" : "result";
                            String pending = m.optString("pendingDelete", "");
                            if (!pending.isEmpty() && !operation.equals(pending)) throw new IOException("请先重试未完成的删除");
                            if (original) {
                                if (source == null) throw new IOException("无法访问原图");
                                if (!pending.isEmpty() && sourceDefinitelyAbsent(c, Uri.parse(m.getString("uri")))) {
                                    m.remove("pendingDelete"); write(file, m); done++; continue;
                                }
                                if (!source.exists() || !fullyDecodable(result) || !m.getString("resultHash").equals(hash(new FileInputStream(result))) || !m.getString("originalHash").equals(hash(source.openInputStream()))) { failed++; continue; }
                                m.put("showOriginal", false); m.put("pendingDelete", operation); write(file, m);
                                if (!source.delete()) { failed++; continue; }
                                m.remove("pendingDelete"); write(file, m);
                            } else {
                                if (source == null || !source.exists() || !m.getString("originalHash").equals(hash(source.openInputStream()))) { failed++; continue; }
                                String expected = m.getString("resultHash");
                                String name = m.optString("pendingResult", "");
                                File temp = null;
                                if (!name.isEmpty()) {
                                    if (!name.startsWith("result-") || !name.endsWith(".tmp") || !name.equals(new File(name).getName())) throw new IOException("无效的恢复记录");
                                    temp = new File(folder(c, gid), name);
                                }
                                if (result.exists() && (!result.isFile() || !expected.equals(hash(new FileInputStream(result))))) throw new IOException("已有结果与记录不符");
                                if (temp != null && temp.exists() && (!temp.isFile() || !expected.equals(hash(new FileInputStream(temp))))) throw new IOException("临时结果与记录不符");
                                m.put("pendingDelete", operation); write(file, m);
                                if (result.exists() && !result.delete()) throw new IOException("无法删除结果");
                                if (temp != null && temp.exists() && !temp.delete()) throw new IOException("无法删除临时结果");
                                File backup = new File(file.getPath() + ".bak"), fresh = new File(file.getPath() + ".new");
                                if ((backup.exists() && !backup.delete()) || (fresh.exists() && !fresh.delete()) || (file.exists() && !file.delete())) throw new IOException("无法删除元数据");
                            }
                            done++;
                        }
                    }
                } catch (Exception e) { failed++; }
            }
        }
        return "已处理 " + done + " 页，跳过或失败 " + failed + " 页";
    }
    private UpscaleStore() {}
}
