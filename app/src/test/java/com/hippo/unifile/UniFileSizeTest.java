package com.hippo.unifile;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Size and query-budget contracts for scanning document-provider downloads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class UniFileSizeTest {
    private SizeProvider provider;
    private UniFile root;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.application;
        provider = new SizeProvider();
        ShadowContentResolver.registerProviderInternal("sizes", provider);
        root = UniFile.fromTreeUri(context, Uri.parse("content://sizes/tree/root"));
        provider.add("root", null, null);
    }

    @Test
    public void nestedTotalsUseQueriesPerDirectoryRatherThanPerImage() {
        provider.add("nested", "root", null);
        for (int i = 0; i < 1000; i++) provider.add("image" + i, "nested", 17L);
        provider.add("empty", "root", 0L);
        assertEquals(17000L, root.getTotalSize());
        assertTrue("One metadata query plus one query per directory", provider.queries <= 3);

        // A new scan must observe growth and deletion without relying on directory timestamps.
        provider.sizes.put("image0", 23L);
        provider.children.get("nested").remove("image1");
        assertEquals(16989L, root.getTotalSize());
        assertTrue(provider.queries <= 6);
    }

    @Test
    public void missingOrUnknownChildrenDoNotProducePlausiblePartialTotals() {
        provider.add("known", "root", 42L);
        provider.add("unknown", "root", -1L);
        assertEquals(-1L, root.getTotalSize());
        provider.sizes.put("unknown", 0L);
        assertEquals(42L, root.getTotalSize());
        provider.unavailable = true;
        assertEquals(-1L, root.getTotalSize());
    }

    @Test
    public void emptyDirectoryAndSingleFileHaveDefinedSizes() {
        assertEquals(0L, root.getTotalSize());
        provider.add("archive", "root", 1234L);
        UniFile archive = UniFile.fromTreeUri(RuntimeEnvironment.application,
                Uri.parse("content://sizes/tree/archive"));
        assertEquals(1234L, archive.getTotalSize());
    }

    public static class SizeProvider extends ContentProvider {
        final Map<String, Long> sizes = new HashMap<>();
        final Map<String, List<String>> children = new HashMap<>();
        int queries;
        boolean unavailable;

        void add(String id, String parent, Long size) {
            sizes.put(id, size);
            if (size == null) children.put(id, new ArrayList<>());
            if (parent != null) children.get(parent).add(id);
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            queries++;
            if (unavailable) return null;
            MatrixCursor cursor = new MatrixCursor(projection);
            String id = DocumentsContract.getDocumentId(uri);
            if ("children".equals(uri.getLastPathSegment())) {
                for (String child : children.get(id)) addRow(cursor, projection, child);
            } else if (sizes.containsKey(id)) {
                addRow(cursor, projection, id);
            }
            return cursor;
        }

        private void addRow(MatrixCursor cursor, String[] columns, String id) {
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(columns[i])) values[i] = id;
                else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(columns[i])) {
                    values[i] = children.containsKey(id)
                            ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
                } else if (DocumentsContract.Document.COLUMN_SIZE.equals(columns[i])) {
                    Long size = sizes.get(id);
                    values[i] = size != null && size >= 0 ? size : null;
                }
            }
            cursor.addRow(values);
        }

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
