package com.hippo.ehviewer.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;

import androidx.cardview.widget.CardView;

import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.widget.TileThumb;
import com.hippo.lib.image.Image;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verify the download-only layout at equal column width after real drawable dimensions arrive. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = DownloadThumbLayoutTest.NoImageLoader.class)
public class DownloadThumbLayoutTest {
    @Test public void loadedCoverControlsHeightAndCardClipsToSmallRoundedOutline() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar);
        controller.setup();
        Bitmap portrait = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888);
        Bitmap landscape = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888);
        try {
            CardView card = (CardView) LayoutInflater.from(activity).inflate(R.layout.item_download_thumb, null);
            TileThumb thumb = card.findViewById(R.id.thumb);
            thumb.setThumbSize(0, 0);
            thumb.setImageDrawable(new BitmapDrawable(activity.getResources(), portrait));
            measure(card);
            assertEquals(180, thumb.getMeasuredWidth());
            assertEquals(360, thumb.getMeasuredHeight());
            assertEquals(360, card.getMeasuredHeight());

            // A later loaded/rebound image must change height without changing column width.
            thumb.setImageDrawable(new BitmapDrawable(activity.getResources(), landscape));
            measure(card);
            assertEquals(180, thumb.getMeasuredWidth());
            assertEquals(90, thumb.getMeasuredHeight());
            assertEquals(90, card.getMeasuredHeight());

            assertTrue(card.getClipToOutline());
            assertFalse(card.getUseCompatPadding());
            assertFalse(card.getPreventCornerOverlap());
            assertEquals(2f * activity.getResources().getDisplayMetrics().density, card.getRadius(), 0.01f);
            card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
            // View.draw supplies the background bounds; layout alone leaves them empty offscreen.
            Bitmap frame = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                card.draw(new Canvas(frame));
                Outline outline = new Outline();
                card.getOutlineProvider().getOutline(card, outline);
                assertFalse("The rendered card must have a non-empty clipping outline", outline.isEmpty());
                assertTrue(outline.canClip());
                Rect bounds = new Rect();
                assertTrue(outline.getRect(bounds));
                assertEquals(new Rect(0, 0, 180, 90), bounds);
                assertEquals(card.getRadius(), outline.getRadius(), 0.01f);
            } finally {
                frame.recycle();
            }
        } finally {
            portrait.recycle();
            landscape.recycle();
            controller.pause().stop().destroy();
        }
    }

    private static void measure(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    /** Layout measurement does not need network/cache/application startup. */
    @Implements(EhApplication.class)
    public static class NoImageLoader {
        @Implementation public static Conaco<Image> getConaco(Context context) { return null; }
    }
}
