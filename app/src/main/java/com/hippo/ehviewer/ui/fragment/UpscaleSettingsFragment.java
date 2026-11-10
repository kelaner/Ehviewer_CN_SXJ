package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.ehviewer.upscale.UpscaleConfig;

public class UpscaleSettingsFragment extends PreferenceFragmentCompat {
    private ListPreference model;
    private ListPreference scale;
    private ListPreference noise;

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof SettingsActivity) {
            ((SettingsActivity) getActivity()).setSettingsTitle(R.string.upscale_settings_title);
        }
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        model = list(screen, UpscaleConfig.KEY_MODEL, R.string.upscale_model_preset,
                R.array.upscale_model_entries, R.array.upscale_model_values, "realesrgan");
        if ("anime2x".equals(model.getValue())) model.setValue("realesrgan");
        scale = list(screen, UpscaleConfig.KEY_SCALE, R.string.upscale_scale,
                R.array.upscale_scale_entries, R.array.upscale_scale_values, "2");
        noise = list(screen, UpscaleConfig.KEY_NOISE, R.string.upscale_noise,
                R.array.upscale_noise_entries, R.array.upscale_noise_values, "1");
        scale.setSummaryProvider(preference -> preference.isEnabled() ? scale.getEntry() : getString(R.string.upscale_fixed_2x));
        noise.setSummaryProvider(preference -> preference.isEnabled() ? noise.getEntry() : getString(R.string.upscale_noise_unavailable));
        list(screen, UpscaleConfig.KEY_TILE, R.string.upscale_tile_size,
                R.array.upscale_tile_entries, R.array.upscale_tile_values, "128")
                .setSummary(R.string.upscale_tile_summary);
        toggle(screen, UpscaleConfig.KEY_FP16_STORAGE, R.string.upscale_fp16_storage,
                R.string.upscale_fp16_storage_summary, true);
        toggle(screen, UpscaleConfig.KEY_FP16_ARITHMETIC, R.string.upscale_fp16_arithmetic,
                R.string.upscale_fp16_arithmetic_summary, false);
        toggle(screen, UpscaleConfig.KEY_WATERMARK, R.string.upscale_watermark,
                R.string.upscale_watermark_summary, true);

        model.setOnPreferenceChangeListener((preference, value) -> {
            updateAvailability(String.valueOf(value));
            return true;
        });
        updateAvailability(model.getValue());

        Preference manage = new Preference(requireContext());
        manage.setTitle(R.string.upscale_manage_title);
        manage.setSummary(R.string.upscale_manage_summary);
        manage.setFragment(UpscaleManageFragment.class.getName());
        screen.addPreference(manage);
    }

    private ListPreference list(PreferenceScreen screen, String key, int title, int entries, int values, String fallback) {
        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setEntries(entries);
        preference.setEntryValues(values);
        preference.setDefaultValue(fallback);
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        screen.addPreference(preference);
        return preference;
    }

    private void toggle(PreferenceScreen screen, String key, int title, int summary, boolean fallback) {
        SwitchPreferenceCompat preference = new SwitchPreferenceCompat(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setDefaultValue(fallback);
        screen.addPreference(preference);
    }

    private void updateAvailability(String value) {
        scale.setEnabled(UpscaleConfig.supportsScale(value));
        noise.setEnabled(UpscaleConfig.supportsNoise(value));

    }
}
