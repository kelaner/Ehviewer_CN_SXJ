package com.hippo.ehviewer.upscale;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

public final class VulkanUpscaler implements AutoCloseable {
    static { System.loadLibrary("vulkan_upscaler"); }
    private long handle;

    public VulkanUpscaler(Context context) {
        handle = nativeCreate(context.getAssets());
        if (handle == 0) throw new IllegalStateException("Vulkan 超分引擎初始化失败");
    }

    public synchronized Bitmap upscale(Bitmap input) {
        if (handle == 0) throw new IllegalStateException("超分引擎已关闭");
        if (input == null || input.isRecycled() || input.getConfig() != Bitmap.Config.ARGB_8888 ||
                input.getWidth() > Integer.MAX_VALUE / 2 || input.getHeight() > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("输入必须是有效的 ARGB_8888 位图");
        }
        Bitmap output = Bitmap.createBitmap(input.getWidth() * 2, input.getHeight() * 2, Bitmap.Config.ARGB_8888);
        try {
            nativeUpscale(handle, input, output);
            return output;
        } catch (RuntimeException | Error e) {
            output.recycle();
            throw e;
        }
    }

    @Override public synchronized void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate(AssetManager assets);
    private static native void nativeUpscale(long handle, Bitmap input, Bitmap output);
    private static native void nativeDestroy(long handle);
}
