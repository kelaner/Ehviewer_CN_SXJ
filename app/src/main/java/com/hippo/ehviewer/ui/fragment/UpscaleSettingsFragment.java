package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.SettingsActivity;

public class UpscaleSettingsFragment extends PreferenceFragmentCompat {
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

        ListPreference model = new ListPreference(requireContext());
        model.setKey("upscale_model_preset");
        model.setTitle(R.string.upscale_model_preset);
        model.setEntries(R.array.upscale_model_entries);
        model.setEntryValues(R.array.upscale_model_values);
        model.setDefaultValue("anime2x");
        model.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        screen.addPreference(model);

        Preference manage = new Preference(requireContext());
        manage.setTitle(R.string.upscale_manage_title);
        manage.setFragment(UpscaleManageFragment.class.getName());
        screen.addPreference(manage);
    }
}
