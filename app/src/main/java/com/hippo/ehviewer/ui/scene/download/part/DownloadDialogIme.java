package com.hippo.ehviewer.ui.scene.download.part;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Restore the activity's resized content after a download label editor closes. */
public final class DownloadDialogIme {
    private DownloadDialogIme() { }

    public static void restoreOnDismiss(AlertDialog dialog, View editor) {
        dialog.setOnDismissListener(ignored -> {
            InputMethodManager ime = (InputMethodManager) editor.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            editor.clearFocus();
            Context context = editor.getContext();
            while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                Context base = ((ContextWrapper) context).getBaseContext();
                if (base == context) break;
                context = base;
            }
            if (!(context instanceof Activity)) return;
            View decor = ((Activity) context).getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener restore = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
                    if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) return;
                    if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    ViewCompat.requestApplyInsets(decor);
                    decor.requestLayout();
                    decor.postInvalidateOnAnimation();
                }
            };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(restore);
            ViewCompat.requestApplyInsets(decor);
            decor.requestLayout();
            decor.postInvalidateOnAnimation();
            // A destroyed window or an IME that never reports hidden must not retain the listener.
            decor.postDelayed(() -> {
                if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnGlobalLayoutListener(restore);
            }, 3000);
        });
    }
}
