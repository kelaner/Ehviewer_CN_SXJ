package com.hippo.ehviewer.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.scene.download.part.DownloadRefreshDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Exercise visible progress, window flags and real request cancellation without network or a database. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = {DownloadRefreshDialogTest.ApplicationClient.class,
                DownloadRefreshDialogTest.CapturedClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadRefreshDialogTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private DownloadRefreshDialog operation;
    private AlertDialog dialog;
    private final AtomicInteger changes = new AtomicInteger();

    @Before public void setUp() {
        CapturedClient.requests.clear();
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        controller.setup();
        Settings.initialize(activity);
        ApplicationClient.client = new EhClient(activity);
    }

    @After public void tearDown() {
        if (operation != null) operation.stop();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
        ApplicationClient.client = null;
        CapturedClient.requests.clear();
    }

    @Test public void keepScreenOnIsOptInAndCancelClearsItAndCancelsEveryRequest() {
        start(items(8));
        CheckBox keepScreenOn = checkBox(dialog.getWindow().getDecorView());
        assertNotNull(keepScreenOn);
        assertFalse(keepScreenOn.isChecked());
        assertFalse(screenOn());
        keepScreenOn.performClick();
        assertTrue(screenOn());
        keepScreenOn.performClick();
        assertFalse(screenOn());
        keepScreenOn.performClick();
        assertTrue(screenOn());

        List<EhRequest> inFlight = new ArrayList<>(CapturedClient.requests);
        assertTrue(inFlight.size() > 1);
        assertTrue(inFlight.size() < 8);
        // Exercise the button the user sees, including its posted click/dismiss callbacks.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertFalse(screenOn());
        for (EhRequest request : inFlight) assertTrue(request.isCancelled());
        // Late network delivery must neither update the UI nor schedule queued items.
        for (EhRequest request : inFlight) request.getCallback().onFailure(new IOException("late failure"));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(inFlight.size(), CapturedClient.requests.size());
        assertEquals(0, changes.get());
    }

    @Test public void onlySelectedSnapshotIsRequestedWithBoundedParallelismAndOutOfOrderProgress() {
        List<DownloadInfo> selected = items(9);
        Set<String> expectedUrls = new HashSet<>();
        for (DownloadInfo info : selected) expectedUrls.add(EhUrl.getGalleryDetailUrl(info.gid, info.token));
        operation = new DownloadRefreshDialog(activity, selected, DownloadRefreshDialog.Mode.METADATA,
                changes::incrementAndGet);
        // The caller may change selection after opening the operation; its original targets are fixed.
        selected.clear();
        selected.add(new DownloadInfo(9999));
        operation.start();
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        CheckBox keepScreenOn = checkBox(dialog.getWindow().getDecorView());
        assertNotNull(keepScreenOn);
        keepScreenOn.performClick();
        assertTrue(screenOn());

        int initialParallelism = CapturedClient.requests.size();
        assertTrue("Refresh should start more than one request", initialParallelism > 1);
        assertTrue("Refresh must leave work queued rather than start the whole selection", initialParallelism < 9);
        assertProgress(9, 0, 0, 0);
        Set<EhRequest> finished = new HashSet<>();
        while (finished.size() < 9) {
            List<EhRequest> pending = new ArrayList<>(CapturedClient.requests);
            pending.removeAll(finished);
            assertFalse("A failure must not abandon remaining selected items", pending.isEmpty());
            assertTrue("In-flight work must stay bounded", pending.size() <= initialParallelism);
            // Complete the newest request before older ones to exercise out-of-order delivery.
            EhRequest request = pending.get(pending.size() - 1);
            assertEquals(EhClient.METHOD_GET_GALLERY_DETAIL, request.getMethod());
            assertFalse(request.isCancelled());
            assertTrue("Only the original selected snapshot may be requested",
                    expectedUrls.remove(request.getArgs()[0].toString()));
            finished.add(request);
            request.getCallback().onFailure(new IOException("failure " + finished.size()));
            assertProgress(9, finished.size(), 0, finished.size());
            shadowOf(Looper.getMainLooper()).idle();
            assertProgress(9, finished.size(), 0, finished.size());
            assertTrue(CapturedClient.requests.size() - finished.size() <= initialParallelism);
        }
        assertTrue(expectedUrls.isEmpty());
        assertEquals(9, CapturedClient.requests.size());
        assertEquals(0, changes.get());
        assertFalse("Completion must release the screen even when every item fails", screenOn());
        assertFalse(keepScreenOn.isEnabled());
        assertEquals(activity.getString(android.R.string.ok),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
    }

    @Test public void systemCancelAlsoReleasesScreenAndStopsQueuedWork() {
        start(items(6));
        CheckBox keepScreenOn = checkBox(dialog.getWindow().getDecorView());
        assertNotNull(keepScreenOn);
        keepScreenOn.performClick();
        assertTrue(screenOn());
        int submitted = CapturedClient.requests.size();
        dialog.cancel();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(screenOn());
        assertFalse(dialog.isShowing());
        for (EhRequest request : CapturedClient.requests) assertTrue(request.isCancelled());
        assertEquals(submitted, CapturedClient.requests.size());
    }

    private void start(List<DownloadInfo> selected) {
        operation = new DownloadRefreshDialog(activity, selected, DownloadRefreshDialog.Mode.METADATA,
                changes::incrementAndGet);
        operation.start();
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
    }

    private boolean screenOn() {
        return (dialog.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    }

    private void assertProgress(int total, int completed, int succeeded, int failed) {
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        String counts = activity.getString(R.string.download_refresh_progress,
                total, completed, succeeded, failed, "");
        assertTrue("Unexpected progress: " + message.getText(), message.getText().toString().startsWith(counts));
    }

    private static List<DownloadInfo> items(int count) {
        List<DownloadInfo> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DownloadInfo info = new DownloadInfo(100 + i);
            info.token = "token" + i;
            info.title = "Selected " + i;
            result.add(info);
        }
        return result;
    }

    private static CheckBox checkBox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox found = checkBox(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Return an isolated client without starting the real application's services. */
    @Implements(EhApplication.class)
    public static class ApplicationClient {
        static EhClient client;
        @Implementation public static EhClient getEhClient(Context context) { return client; }
    }

    /** Keep real EhRequest instances and callbacks; do not invoke transport or private state. */
    @Implements(EhClient.class)
    public static class CapturedClient {
        static final List<EhRequest> requests = new ArrayList<>();
        @Implementation public void __constructor__(Context context) { }
        @Implementation public void execute(EhRequest request) { requests.add(request); }
    }
}
