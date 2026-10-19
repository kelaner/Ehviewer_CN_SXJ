#include <stdlib.h>
#include <string.h>

#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/log.h>

#include <GLES2/gl2.h>
#include <jni.h>

#define TAG "ImageDecoder_wrapper"

#include "ehviewer.h"

#define IMAGE_TILE_MAX_SIZE (512 * 512)

bool copyPixels(const void *src, int src_w, int src_h, int src_stride, int src_x, int src_y,
                 void *dst, int dst_w, int dst_h, int dst_stride, int dst_x, int dst_y,
                 int width, int height) {
    int left;
    int line;
    size_t line_stride;
    int src_pos;
    int dst_pos;

    // Sanitize
    if (src_x < 0) {
        width += src_x;
        dst_x -= src_x;
        src_x = 0;
    }
    if (dst_x < 0) {
        width += dst_x;
        src_x -= dst_x;
        dst_x = 0;
    }
    if (width <= 0) {
        return false;
    }
    if (src_y < 0) {
        height += src_y;
        dst_y -= src_y;
        src_y = 0;
    }
    if (dst_y < 0) {
        height += dst_y;
        src_y -= dst_y;
        dst_y = 0;
    }
    if (height <= 0) {
        return false;
    }
    left = src_x + width - src_w;
    if (left > 0) {
        width -= left;
    }
    left = dst_x + width - dst_w;
    if (left > 0) {
        width -= left;
    }
    if (width <= 0) {
        return false;
    }
    left = src_y + height - src_h;
    if (left > 0) {
        height -= left;
    }
    left = dst_y + height - dst_h;
    if (left > 0) {
        height -= left;
    }
    if (height <= 0) {
        return false;
    }

    // Init
    line_stride = (size_t) (width * 4);
    src_pos = src_y * src_stride + src_x * 4;
    dst_pos = dst_y * dst_stride + dst_x * 4;


    for (line = 0; line < height; line++) {
        memcpy(dst + dst_pos, src + src_pos, line_stride);
        dst_pos += dst_stride;
        src_pos += src_stride;
    }

    return true;
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_ImageKt_nativeTexImage(JNIEnv *env, jclass clazz, jobject bitmap, jboolean init,
                                          jint offset_x, jint offset_y, jint width, jint height) {
    if (width <= 0 || height <= 0 || width > IMAGE_TILE_MAX_SIZE / height)
        return;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    size_t tile_size = (size_t) width * (size_t) height * 4;
    unsigned char *tile_buffer = (unsigned char *) calloc(1, tile_size);
    if (tile_buffer == NULL || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        free(tile_buffer);
        return;
    }
    copyPixels(pixels, info.width, info.height, info.stride, offset_x, offset_y,
               tile_buffer, width, height, width * 4, 0, 0, width, height);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (init) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     tile_buffer);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE,
                        tile_buffer);
    }
    free(tile_buffer);
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_Image_nativeRender(JNIEnv *env, jclass clazz, jobject srcBitmap, jint src_x,
                                            jint src_y, jobject dst, jint dst_x, jint dst_y, jint width,
                                            jint height) {
    AndroidBitmapInfo dstInfo;
    AndroidBitmapInfo srcInfo;
    void *srcPixels = NULL;
    void *dstPixels = NULL;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dst, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return;
    }
    if (AndroidBitmap_lockPixels(env, dst, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return;
    }

    copyPixels(srcPixels, srcInfo.width, srcInfo.height, srcInfo.stride, src_x, src_y,
               dstPixels, dstInfo.width, dstInfo.height, dstInfo.stride, dst_x, dst_y,
               width, height);

    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, srcBitmap);
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_Image_nativeTexImage(JNIEnv *env, jclass clazz, jobject bitmap, jboolean init,
                                              jint offset_x, jint offset_y, jint width, jint height) {
    if (width <= 0 || height <= 0 || width > IMAGE_TILE_MAX_SIZE / height)
        return;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    size_t tile_size = (size_t) width * (size_t) height * 4;
    unsigned char *tile_buffer = (unsigned char *) calloc(1, tile_size);
    if (tile_buffer == NULL || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        free(tile_buffer);
        return;
    }
    copyPixels(pixels, info.width, info.height, info.stride, offset_x, offset_y,
               tile_buffer, width, height, width * 4, 0, 0, width, height);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (init) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     tile_buffer);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE,
                        tile_buffer);
    }
    free(tile_buffer);
}