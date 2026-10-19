package com.hippo.ehviewer.ui.scene.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.app.EditTextDialogBuilder;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Download grouping drawer. Raw identities are independent of translated labels. */
public class DownloadLabelDraw {
    private final LayoutInflater inflater;
    private final DownloadsScene scene;
    private final ViewGroup container;
    private final Context context;
    private Toolbar toolbar;
    private TextView title;
    private View add;
    private GroupAdapter adapter;
    private ItemTouchHelper touchHelper;
    private boolean movingLabel;
    private final List<Group> groups = new ArrayList<>();
    private static final String[] TYPES = {"custom", "artist", "character", "group"};

    private static final class Group {
        final String key;
        final String title;
        final long count;
        Group(String key, String title, long count) {
            this.key = key;
            this.title = title;
            this.count = count;
        }
        boolean editable() { return key.startsWith("label:"); }
    }

    public DownloadLabelDraw(LayoutInflater inflater, @Nullable ViewGroup container, DownloadsScene scene) {
        this.inflater = inflater;
        this.container = container;
        this.scene = scene;
        context = scene.getEHContext();
    }

    public View createView() {
        View view = inflater.inflate(R.layout.download_groups_draw, container, false);
        toolbar = view.findViewById(R.id.toolbar);
        title = view.findViewById(R.id.download_group_title);
        add = view.findViewById(R.id.download_group_add);
        add.setOnClickListener(v -> editLabel(null));
        toolbar.inflateMenu(R.menu.drawer_download);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                String[] names = context.getResources().getStringArray(R.array.download_group_types);
                int selected = 0;
                for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(scene.getGrouping())) selected = i;
                new AlertDialog.Builder(context).setTitle(R.string.download_group_type)
                        .setSingleChoiceItems(names, selected, (dialog, which) -> {
                            String type = TYPES[which];
                            scene.selectGroup(type, Settings.getDownloadGroupSelection(type));
                            dialog.dismiss();
                        }).setNegativeButton(android.R.string.cancel, null).show();
                return true;
            }
            if (item.getItemId() == R.id.action_default_download_label) {
                showDefaultLabel();
                return true;
            }
            return false;
        });
        RecyclerView list = view.findViewById(R.id.list_view);
        list.setLayoutManager(new LinearLayoutManager(context));
        adapter = new GroupAdapter();
        list.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override public int getMovementFlags(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
                int position = holder.getBindingAdapterPosition();
                return makeMovementFlags("custom".equals(scene.getGrouping()) && position >= 0
                        && groups.get(position).editable() ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
            }
            @Override public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder from,
                                            @NonNull RecyclerView.ViewHolder to) {
                int start = from.getBindingAdapterPosition();
                int end = to.getBindingAdapterPosition();
                if (start < 2 || end < 2 || !"custom".equals(scene.getGrouping())) return false;
                movingLabel = true;
                try {
                    scene.getMDownloadManager().moveLabel(start - 2, end - 2);
                } finally {
                    movingLabel = false;
                }
                Group item = groups.remove(start);
                groups.add(end, item);
                adapter.notifyItemMoved(start, end);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) { }
        });
        touchHelper.attachToRecyclerView(list);
        updateDownloadLabels();
        return view;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateDownloadLabels() {
        if (adapter == null || movingLabel) return;
        DownloadManager manager = scene.getMDownloadManager();
        if (manager == null) return;
        boolean custom = "custom".equals(scene.getGrouping());
        int type = 0;
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(scene.getGrouping())) type = i;
        title.setText(context.getResources().getStringArray(R.array.download_group_types)[type]);
        add.setVisibility(custom ? View.VISIBLE : View.GONE);
        toolbar.getMenu().findItem(R.id.action_default_download_label).setVisible(custom);
        groups.clear();
        List<DownloadInfo> all = new ArrayList<>(manager.getAllDownloadInfoList());
        Set<Long> allIds = new HashSet<>();
        for (DownloadInfo info : all) allIds.add(info.gid);
        groups.add(new Group("@all", context.getString(R.string.download_group_all), allIds.size()));
        if (custom) {
            groups.add(new Group("@default", context.getString(R.string.default_download_label_name),
                    manager.getDefaultDownloadInfoList().size()));
            for (DownloadLabel label : manager.getLabelList()) {
                groups.add(new Group("label:" + label.getLabel(), label.getLabel(), manager.getLabelCount(label.getLabel())));
            }
        } else {
            Map<String, Set<Long>> members = new HashMap<>();
            Map<String, DownloadTags.Tag> tags = new HashMap<>();
            Set<Long> unknown = new HashSet<>();
            for (DownloadInfo info : all) {
                boolean known = false;
                for (DownloadTags.Tag tag : DownloadTags.getTags(info.gid)) {
                    if (!scene.getGrouping().equals(tag.namespace)) continue;
                    known = true;
                    tags.put(tag.identity(), tag);
                    Set<Long> ids = members.get(tag.identity());
                    if (ids == null) { ids = new HashSet<>(); members.put(tag.identity(), ids); }
                    ids.add(info.gid);
                }
                if (!known) unknown.add(info.gid);
            }
            groups.add(new Group("@unknown", context.getString(R.string.unknown), unknown.size()));
            List<Group> metadata = new ArrayList<>();
            for (Map.Entry<String, Set<Long>> entry : members.entrySet()) {
                metadata.add(new Group(entry.getKey(), tags.get(entry.getKey()).getDisplayName(context), entry.getValue().size()));
            }
            Collections.sort(metadata, (left, right) -> {
                int count = Long.compare(right.count, left.count);
                if (count != 0) return count;
                String leftRaw = tags.get(left.key).raw;
                String rightRaw = tags.get(right.key).raw;
                int raw = leftRaw.compareToIgnoreCase(rightRaw);
                return raw != 0 ? raw : leftRaw.compareTo(rightRaw);
            });
            groups.addAll(metadata);
        }
        adapter.notifyDataSetChanged();
    }

    private void editLabel(@Nullable String original) {
        DownloadManager manager = scene.getMDownloadManager();
        if (manager == null) return;
        EditTextDialogBuilder builder = new EditTextDialogBuilder(context, original, context.getString(R.string.download_labels));
        builder.setTitle(original == null ? R.string.new_label_title : R.string.rename_label_title);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.show();
        com.hippo.ehviewer.ui.scene.download.part.DownloadDialogIme.restoreOnDismiss(dialog, builder.getEditText());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = builder.getText();
            if (text.isEmpty()) builder.setError(context.getString(R.string.label_text_is_empty));
            else if (context.getString(R.string.default_download_label_name).equals(text))
                builder.setError(context.getString(R.string.label_text_is_invalid));
            else if (!text.equals(original) && manager.containLabel(text))
                builder.setError(context.getString(R.string.label_text_exist));
            else {
                if (original == null) manager.addLabel(text);
                else if (!original.equals(text)) manager.renameLabel(original, text);
                dialog.dismiss();
                updateDownloadLabels();
            }
        });
    }

    private void labelActions(Group group) {
        String label = group.key.substring(6);
        new AlertDialog.Builder(context).setTitle(group.title)
                .setItems(new String[]{context.getString(R.string.rename_label_title), context.getString(R.string.delete_label_title)},
                        (dialog, which) -> {
                            if (which == 0) editLabel(label);
                            else new AlertDialog.Builder(context).setTitle(R.string.delete_label_title)
                                    .setMessage(context.getString(R.string.delete_label_message, label))
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(android.R.string.ok, (confirmation, button) -> {
                                        scene.getMDownloadManager().deleteLabel(label);
                                        scene.updateForLabel();
                                        updateDownloadLabels();
                                    }).show();
                        }).show();
    }

    private void showDefaultLabel() {
        DownloadManager manager = scene.getMDownloadManager();
        if (manager == null) return;
        List<DownloadLabel> labels = manager.getLabelList();
        String[] names = new String[labels.size() + 2];
        names[0] = context.getString(R.string.let_me_select);
        names[1] = context.getString(R.string.default_download_label_name);
        for (int i = 0; i < labels.size(); i++) names[i + 2] = labels.get(i).getLabel();
        new AlertDialog.Builder(context).setTitle(R.string.default_download_label).setItems(names, (dialog, which) -> {
            Settings.putHasDefaultDownloadLabel(which != 0);
            if (which != 0) Settings.putDefaultDownloadLabel(which == 1 ? null : names[which]);
        }).show();
    }

    private final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;
        final View handle;
        final View menu;
        GroupHolder(View view) {
            super(view);
            name = view.findViewById(R.id.text1);
            count = view.findViewById(R.id.text2);
            handle = view.findViewById(R.id.drag_handler);
            menu = view.findViewById(R.id.download_label_menu);
        }
    }

    private final class GroupAdapter extends RecyclerView.Adapter<GroupHolder> {
        @NonNull @Override public GroupHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new GroupHolder(inflater.inflate(R.layout.item_download_group, parent, false));
        }
        @SuppressLint("ClickableViewAccessibility")
        @Override public void onBindViewHolder(@NonNull GroupHolder holder, int position) {
            Group group = groups.get(position);
            holder.name.setText(group.title);
            holder.count.setText(Long.toString(group.count));
            holder.name.setTypeface(null, group.key.equals(scene.getGroupSelection())
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            holder.handle.setVisibility("custom".equals(scene.getGrouping()) && group.editable() ? View.VISIBLE : View.GONE);
            holder.handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder);
                return false;
            });
            holder.itemView.setOnClickListener(v -> {
                scene.selectGroup(scene.getGrouping(), group.key);
                scene.closeDrawer(Gravity.RIGHT);
            });
            holder.menu.setVisibility("custom".equals(scene.getGrouping()) && group.editable() ? View.VISIBLE : View.GONE);
            holder.menu.setOnClickListener(v -> labelActions(group));
        }
        @Override public int getItemCount() { return groups.size(); }
    }
}
