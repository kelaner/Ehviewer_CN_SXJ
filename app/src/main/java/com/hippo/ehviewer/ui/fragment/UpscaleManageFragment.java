package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.ehviewer.upscale.UpscaleStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpscaleManageFragment extends PreferenceFragmentCompat {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Long> selected = new HashSet<>();
    private boolean busy;

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof SettingsActivity) {
            ((SettingsActivity) getActivity()).setSettingsTitle(R.string.upscale_manage_title);
        }
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        load();
    }

    private void load() {
        busy = true;
        showMessage(getString(R.string.upscale_loading));
        Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            try {
                List<UpscaleStore.Entry> entries = UpscaleStore.list(context);
                main.post(() -> {
                    if (!isAdded()) return;
                    busy = false;
                    display(entries);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!isAdded()) return;
                    busy = false;
                    showMessage(getString(R.string.upscale_failed, e.getMessage()));
                });
            }
        });
    }

    private void showMessage(String message) {
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        Preference item = new Preference(requireContext());
        item.setTitle(message);
        item.setSelectable(false);
        screen.addPreference(item);
    }

    private void display(List<UpscaleStore.Entry> entries) {
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        selected.clear();
        if (entries.isEmpty()) {
            showMessage(getString(R.string.upscale_empty));
            return;
        }
        addDeleteAction(screen, false);
        addDeleteAction(screen, true);
        for (UpscaleStore.Entry entry : entries) {
            CheckBoxPreference item = new CheckBoxPreference(requireContext());
            item.setPersistent(false);
            item.setTitle(entry.title);
            item.setSummary(getString(R.string.upscale_entry_summary, entry.pageCount,
                    Formatter.formatFileSize(requireContext(), entry.originalBytes),
                    Formatter.formatFileSize(requireContext(), entry.upscaledBytes), entry.modelPreset));
            item.setOnPreferenceChangeListener((preference, value) -> {
                if ((Boolean) value) selected.add(entry.galleryId);
                else selected.remove(entry.galleryId);
                return true;
            });
            screen.addPreference(item);
        }
    }

    private void addDeleteAction(PreferenceScreen screen, boolean original) {
        Preference action = new Preference(requireContext());
        action.setTitle(original ? R.string.upscale_delete_originals : R.string.upscale_delete_results);
        action.setOnPreferenceClickListener(preference -> {
            if (busy) return true;
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.upscale_select_first, Toast.LENGTH_SHORT).show();
                return true;
            }
            List<Long> ids = new ArrayList<>(selected);
            new AlertDialog.Builder(requireContext())
                    .setMessage(getString(original ? R.string.upscale_confirm_originals : R.string.upscale_confirm_results, ids.size()))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> delete(ids, original))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(action);
    }

    private void delete(List<Long> ids, boolean original) {
        busy = true;
        showMessage(getString(R.string.upscale_loading));
        Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            String message;
            try {
                message = UpscaleStore.delete(context, ids, original);
            } catch (Exception e) {
                message = context.getString(R.string.upscale_failed, e.getMessage());
            }
            String result = message;
            main.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
