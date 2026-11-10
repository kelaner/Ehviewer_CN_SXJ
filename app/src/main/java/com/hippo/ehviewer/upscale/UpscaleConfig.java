package com.hippo.ehviewer.upscale;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable snapshot of the settings used for one upscale job. */
public final class UpscaleConfig {
    public static final String KEY_MODEL = "upscale_model_preset";
    public static final String KEY_SCALE = "upscale_scale";
    public static final String KEY_NOISE = "upscale_noise";
    public static final String KEY_TILE = "upscale_tile_size";
    public static final String KEY_FP16_STORAGE = "upscale_fp16_storage";
    public static final String KEY_FP16_ARITHMETIC = "upscale_fp16_arithmetic";
    public static final String KEY_WATERMARK = "upscale_watermark";

    public final String model;
    public final int scale;
    public final int noise;
    public final int tileSize;
    public final boolean fp16Storage;
    public final boolean fp16Arithmetic;

    private UpscaleConfig(String model, int scale, int noise, int tileSize,
                          boolean fp16Storage, boolean fp16Arithmetic) {
        this.model = sanitizeModel(model);
        this.scale = sanitizeScale(this.model, scale);
        this.noise = Math.max(-1, Math.min(3, noise));
        this.tileSize = sanitizeTile(tileSize);
        this.fp16Storage = fp16Storage;
        this.fp16Arithmetic = fp16Arithmetic;
    }

    public static UpscaleConfig from(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return new UpscaleConfig(
                preferences.getString(KEY_MODEL, "realesrgan"),
                integer(preferences, KEY_SCALE, 2), integer(preferences, KEY_NOISE, 1),
                integer(preferences, KEY_TILE, 128),
                preferences.getBoolean(KEY_FP16_STORAGE, true),
                preferences.getBoolean(KEY_FP16_ARITHMETIC, false));
    }

    public static UpscaleConfig fromJson(JSONObject value) throws JSONException {
        return new UpscaleConfig(value.optString("model", "realesrgan"),
                value.optInt("scale", 2), value.optInt("noise", 1), value.optInt("tile", 128),
                value.optBoolean("fp16Storage", true), value.optBoolean("fp16Arithmetic", false));
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("model", model).put("scale", scale).put("noise", noise)
                .put("tile", tileSize).put("fp16Storage", fp16Storage)
                .put("fp16Arithmetic", fp16Arithmetic);
    }

    public String assetStem() {
        switch (model) {
            case "realcugan":
                String denoise = noise < 0 ? "no-denoise" : noise == 0 ? "conservative" : "denoise" + noise + "x";
                return "upscale/realcugan/up2x-" + denoise;
            case "ultracompact": return "upscale/ultracompact/2x-sudo-UltraCompact";
            case "span": return "upscale/span/2x-NomosUni-SPAN-multijpg-ldl";
            default: return "upscale/realesrgan/v3-anime/x" + scale;
        }
    }

    public int padding() {
        if ("realcugan".equals(model)) return 18;
        if ("span".equals(model)) return 24;
        return 10;
    }

    public String displayName() {
        switch (model) {
            case "realcugan": return "Real-CUGAN SE " + scale + "x（降噪 " + noiseLabel() + "）";
            case "ultracompact": return "sudo UltraCompact 2x";
            case "span": return "NomosUni SPAN 2x";
            default: return "Real-ESRGAN AnimeVideo v3 " + scale + "x";
        }
    }

    private String noiseLabel() {
        return noise < 0 ? "关闭" : noise == 0 ? "保守" : Integer.toString(noise);
    }

    public static boolean watermarkEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_WATERMARK, true);
    }

    public static boolean supportsScale(String model) { return "realesrgan".equals(sanitizeModel(model)); }
    public static boolean supportsNoise(String model) { return "realcugan".equals(sanitizeModel(model)); }

    private static String sanitizeModel(String model) {
        if ("anime2x".equals(model)) return "realesrgan";
        if ("realcugan".equals(model) || "ultracompact".equals(model) || "span".equals(model)) return model;
        return "realesrgan";
    }

    private static int sanitizeScale(String model, int scale) {
        if (!"realesrgan".equals(model)) return 2;
        return scale == 3 || scale == 4 ? scale : 2;
    }

    private static int sanitizeTile(int tile) {
        return tile == 64 || tile == 96 || tile == 192 || tile == 256 ? tile : 128;
    }

    private static int integer(SharedPreferences preferences, String key, int fallback) {
        try { return Integer.parseInt(preferences.getString(key, Integer.toString(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
