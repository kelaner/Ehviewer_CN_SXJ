package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadTags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local metadata editing never resolves translated text back into a raw identity. */
public final class DownloadLocalTagDialog {
    private static final String[] TYPES = {"artist", "character", "group"};

    private DownloadLocalTagDialog() { }

    public static void showAdd(Context context, List<DownloadInfo> selection, Runnable changed) {
        List<DownloadInfo> finished = finished(selection);
        if (finished.isEmpty()) {
            Toast.makeText(context, R.string.download_local_tags_finished_only, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(context.getResources().getDisplayMetrics().density * 20);
        content.setPadding(padding, 0, padding, 0);
        TextView note = new TextView(context);
        note.setText(R.string.download_local_tags_finished_only);
        content.addView(note);
        Spinner type = new Spinner(context);
        type.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                context.getResources().getStringArray(R.array.download_metadata_types)));
        content.addView(type);
        AutoCompleteTextView input = new AutoCompleteTextView(context);
        input.setSingleLine(true);
        input.setThreshold(1);
        input.setHint(R.string.download_local_tag_raw_hint);
        content.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Suggestions suggestions = new Suggestions(context, type, knownTags(context, selection));
        input.setAdapter(suggestions);
        input.setOnItemClickListener((parent, view, position, id) -> {
            DownloadTags.Tag tag = suggestions.getItem(position);
            input.setText(tag.raw, false);
            input.setSelection(input.length());
        });
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                input.dismissDropDown();
                suggestions.getFilter().filter(input.getText());
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.download_local_tags_add)
                .setView(content).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            String namespace = TYPES[type.getSelectedItemPosition()];
            int colon = text.indexOf(':');
            if (colon >= 0) {
                DownloadTags.Tag named = new DownloadTags.Tag(text.substring(0, colon), text.substring(colon + 1));
                if (!namespace.equals(named.namespace)) {
                    input.setError(context.getString(R.string.download_local_tag_type_mismatch));
                    return;
                }
                text = named.raw;
            }
            if (text.isEmpty()) {
                input.setError(context.getString(R.string.label_text_is_empty));
                return;
            }
            DownloadTags.addLocalTags(finished, namespace, text);
            dialog.dismiss();
            changed.run();
        }));
        dialog.show();
    }

    public static void showClear(Context context, List<DownloadInfo> selection, Runnable changed) {
        List<DownloadInfo> finished = finished(selection);
        if (finished.isEmpty()) {
            Toast.makeText(context, R.string.download_local_tags_finished_only, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(context).setTitle(R.string.download_local_tags_clear)
                .setMessage(R.string.download_local_tags_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    DownloadTags.clearLocalTags(finished);
                    changed.run();
                }).show();
    }

    private static List<DownloadInfo> finished(List<DownloadInfo> selection) {
        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : selection) if (info.state == DownloadInfo.STATE_FINISH) result.add(info);
        return result;
    }

    private static List<DownloadTags.Tag> knownTags(Context context, List<DownloadInfo> selection) {
        Map<String, DownloadTags.Tag> tags = new LinkedHashMap<>();
        EhTagDatabase database = EhTagDatabase.getInstance(context);
        if (database != null) for (com.hippo.ehviewer.client.data.Tag translated : database.getTagList()) {
            String identity = translated.english;
            int colon = identity.indexOf(':');
            if (colon < 0) continue;
            DownloadTags.Tag tag = new DownloadTags.Tag(identity.substring(0, colon), identity.substring(colon + 1));
            tags.put(tag.identity(), tag);
        }
        for (DownloadInfo info : selection) for (DownloadTags.Tag tag : DownloadTags.getTags(info.gid))
            tags.put(tag.identity(), tag);
        return new ArrayList<>(tags.values());
    }

    private static final class Suggestions extends BaseAdapter implements Filterable {
        private final Context context;
        private final Spinner type;
        private final List<DownloadTags.Tag> all;
        private List<DownloadTags.Tag> visible = new ArrayList<>();
        private volatile String namespace = TYPES[0];
        Suggestions(Context context, Spinner type, List<DownloadTags.Tag> all) {
            this.context = context;
            this.type = type;
            this.all = all;
        }
        @Override public int getCount() { return visible.size(); }
        @Override public DownloadTags.Tag getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView view = recycled instanceof TextView ? (TextView) recycled : new TextView(context);
            DownloadTags.Tag tag = getItem(position);
            String name = tag.getDisplayName(context);
            view.setText(name.equals(tag.raw) ? tag.namespace + ":" + tag.raw : name + " (" + tag.namespace + ":" + tag.raw + ")");
            int padding = Math.round(context.getResources().getDisplayMetrics().density * 12);
            view.setPadding(padding, padding, padding, padding);
            return view;
        }
        @Override public Filter getFilter() {
            namespace = TYPES[type.getSelectedItemPosition()];
            final String selectedType = namespace;
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence constraint) {
                    String query = constraint == null ? "" : constraint.toString().trim().toLowerCase(Locale.ROOT);
                    List<DownloadTags.Tag> matches = new ArrayList<>();
                    if (!query.isEmpty()) for (DownloadTags.Tag tag : all) {
                        if (selectedType.equals(tag.namespace) && tag.identity().contains(query)) {
                            matches.add(tag);
                            if (matches.size() == 40) break;
                        }
                    }
                    FilterResults results = new FilterResults();
                    results.values = matches;
                    results.count = matches.size();
                    return results;
                }
                @SuppressWarnings("unchecked")
                @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                    if (!selectedType.equals(namespace)) return;
                    visible = (List<DownloadTags.Tag>) results.values;
                    notifyDataSetChanged();
                }
                @Override public CharSequence convertResultToString(Object result) {
                    return ((DownloadTags.Tag) result).raw;
                }
            };
        }
    }
}
