/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.download;

import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.download.DownloadTags;
import com.hippo.ehviewer.ui.scene.download.part.DownloadListProjection;
import com.hippo.ehviewer.event.SomethingNeedRefresh;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAlbumImporter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadArchiveImporter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadBatchActions;
import com.hippo.ehviewer.ui.scene.download.part.DownloadChoiceListener;
import com.hippo.ehviewer.ui.scene.download.part.DownloadGuideHelper;
import com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController;
import com.hippo.ehviewer.ui.scene.download.part.DownloadSearchController;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.ripple.Ripple;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class DownloadsScene extends ToolbarScene
        implements DownloadManager.DownloadInfoListener, DownloadSearchCallback,
        MyEasyRecyclerView.OnItemClickListener,
        MyEasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener,
        SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener,
        DownloadAdapter.DownloadAdapterCallback,
        DownloadArchiveImporter.Host, DownloadAlbumImporter.Host, DownloadSearchController.Host,
        DownloadBatchActions.Host, DownloadPaginationController.Host,
        DownloadGuideHelper.Host, DownloadChoiceListener.Host {

    private static final String TAG = DownloadsScene.class.getSimpleName();

    public static final String KEY_GID = "gid";

    public static final String KEY_ACTION = "action";
    private static final String KEY_LABEL = "label";

    public static final String ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service";

    public static final int LOCAL_GALLERY_INFO_CHANGE = 909;

    private static final long ANIMATE_TIME = 300L;

    @Nullable
    private AddDeleteDrawable mActionFabDrawable;

    /*---------------
         Whole life cycle
         ---------------*/
    @Nullable
    private DownloadManager mDownloadManager;
    @Nullable
    public String mLabel;
    @Nullable
    private List<DownloadInfo> mList;
    @Nullable
    private List<DownloadInfo> mBackList;
    private final DownloadListProjection mProjection = new DownloadListProjection();
    private String mGrouping;
    private String mGroupSelection;
    private boolean mThumbnailMode;
    private com.hippo.ehviewer.download.DownloadMetadataLoader mMetadataLoader;
    private com.hippo.ehviewer.ui.scene.download.part.DownloadRefreshDialog mRefreshDialog;
    private boolean mPageCountScanRunning;

    public String getGrouping() { return mGrouping; }
    public String getGroupSelection() { return mGroupSelection; }
    public boolean isThumbnailMode() { return mThumbnailMode; }

    public void selectGroup(String type, String selection) {
        mGrouping = type;
        mGroupSelection = selection;
        Settings.putDownloadGrouping(type);
        Settings.putDownloadGroupSelection(type, selection);
        if ("custom".equals(type) && !"@all".equals(selection)) {
            mLabel = selection.startsWith("label:") ? selection.substring(6) : null;
            Settings.putRecentDownloadLabel(mLabel);
        }
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void selectCondition(int id) {
        mProjection.select(id);
        updateForLabel();
        if (id == R.id.sort_by_file_size_asc || id == R.id.sort_by_file_size_desc) {
            if (mDownloadManager == null) return;
            List<DownloadInfo> snapshot = new ArrayList<>(mDownloadManager.getAllDownloadInfoList());
            com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                DownloadListProjection.loadFileSizes(snapshot);
                runOnUiThread(() -> {
                    if (mAdapter != null) updateForLabel();
                });
            });
        } else if (id == R.id.sort_by_pages_asc || id == R.id.sort_by_pages_desc) {
            if (mDownloadManager == null || mPageCountScanRunning) return;
            mPageCountScanRunning = true;
            Context context = getEHContext();
            List<DownloadInfo> snapshot = new ArrayList<>(mDownloadManager.getAllDownloadInfoList());
            com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                try {
                    DownloadListProjection.loadPageCounts(context, snapshot);
                } finally {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        mPageCountScanRunning = false;
                        if (mAdapter != null) updateForLabel();
                    });
                }
            });
        }
    }

    private final DownloadArchiveImporter mArchiveImporter = new DownloadArchiveImporter(this);
    private final DownloadAlbumImporter mAlbumImporter = new DownloadAlbumImporter(this);
    private final DownloadSearchController mSearchController = new DownloadSearchController(this);
    private final DownloadBatchActions mBatchActions = new DownloadBatchActions(this);
    private final DownloadPaginationController mPaginationController = new DownloadPaginationController(this);
    private final DownloadGuideHelper mGuideHelper = new DownloadGuideHelper(this);

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private MyEasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private FabLayout mFabLayout;
    @Nullable
    private RecyclerView.Adapter mAdapter;
    @Nullable
    private DownloadAdapter mOriginalAdapter;
    @Nullable
    private AutoStaggeredGridLayoutManager mLayoutManager;

    // 拖拽管理器
    @Nullable
    private RecyclerViewDragDropManager mDragDropManager;

    private ProgressView mProgressView;

    private DownloadLabelDraw downloadLabelDraw;
    public String searchKey = null;

    private int mInitPosition = -1;

    public boolean searching = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mPaginationController::updateReadProcess
    );

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mArchiveImporter::handleSelectedFile
    );

    @NonNull
    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mAlbumImporter::handleSelectedFolder
    );

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_downloads;
    }

    private boolean handleArguments(Bundle args) {
        if (null == args) {
            return false;
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE.equals(args.getString(KEY_ACTION))) {
            DownloadService.Companion.clear();
        }

        long gid;
        if (null != mDownloadManager && -1L != (gid = args.getLong(KEY_GID, -1L))) {
            DownloadInfo info = mDownloadManager.getDownloadInfo(gid);
            if (null != info) {
                mLabel = info.getLabel();
                mGrouping = "custom";
                mGroupSelection = mLabel == null ? "@default" : "label:" + mLabel;
                updateForLabel();
                updateView();

                // Get position
                if (null != mList) {
                    int position = mList.indexOf(info);
                    if (position >= 0 && null != mRecyclerView) {
                        mPaginationController.initPage(position);
                    } else {
                        mInitPosition = position;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNewArguments(@NonNull Bundle args) {
        handleArguments(args);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        mDownloadManager = EhApplication.getDownloadManager(context);
        mDownloadManager.addDownloadInfoListener(this);
        mGrouping = Settings.getDownloadGrouping();
        mGroupSelection = Settings.getDownloadGroupSelection(mGrouping);
        mPaginationController.setCanPagination(Settings.getDownloadPagination());
        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        mList = null;

        DownloadManager manager = mDownloadManager;
        if (null == manager) {
            Context context = getEHContext();
            if (null != context) {
                manager = EhApplication.getDownloadManager(context);
            }
        } else {
            mDownloadManager = null;
        }

        if (null != manager) {
            manager.removeDownloadInfoListener(this);
        } else {
            Log.e(TAG, "Can't removeDownloadInfoListener");
        }
        mActionFabDrawable = null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateForLabel() {
        if (null == mDownloadManager) {
            return;
        }

        List<DownloadInfo> source = new ArrayList<>(mDownloadManager.getAllDownloadInfoList());
        if ("custom".equals(mGrouping)) {
            if (!"@all".equals(mGroupSelection)) {
                if (mGroupSelection.startsWith("label:")) {
                    mLabel = mGroupSelection.substring(6);
                    List<DownloadInfo> labelList = mDownloadManager.getLabelDownloadInfoList(mLabel);
                    if (labelList == null) {
                        mLabel = null;
                        mGroupSelection = "@default";
                        Settings.putDownloadGroupSelection(mGrouping, mGroupSelection);
                        source = new ArrayList<>(mDownloadManager.getDefaultDownloadInfoList());
                    } else source = new ArrayList<>(labelList);
                } else {
                    mLabel = null;
                    source = new ArrayList<>(mDownloadManager.getDefaultDownloadInfoList());
                }
            }
        } else if (!"@all".equals(mGroupSelection)) {
            List<DownloadInfo> grouped = new ArrayList<>();
            for (DownloadInfo info : source) {
                boolean known = false;
                boolean matches = false;
                for (DownloadTags.Tag tag : DownloadTags.getTags(info.gid)) {
                    if (!mGrouping.equals(tag.namespace)) continue;
                    known = true;
                    if (mGroupSelection.equals(tag.identity())) matches = true;
                }
                if (matches || (!known && "@unknown".equals(mGroupSelection))) grouped.add(info);
            }
            source = grouped;
        }
        mBackList = source;
        rebuildDisplayedList();
        mPaginationController.queryUnreadSpiderInfo();
    }

    private void rebuildDisplayedList() {
        if (mBackList == null) return;
        List<DownloadInfo> next = mProjection.apply(getEHContext(), mBackList, searchKey);
        java.util.Set<Long> selected = new java.util.HashSet<>();
        boolean choice = mRecyclerView != null && mRecyclerView.isInCustomChoice();
        boolean reordered = mList == null || mList.size() != next.size();
        if (!reordered) for (int i = 0; i < next.size(); i++) {
            if (mList.get(i).gid != next.get(i).gid) { reordered = true; break; }
        }
        if (choice && reordered) {
            android.util.SparseBooleanArray checked = mRecyclerView.getCheckedItemPositions();
            for (int i = 0; i < checked.size(); i++) {
                int index = positionInList(checked.keyAt(i));
                if (checked.valueAt(i) && mList != null && index >= 0 && index < mList.size())
                    selected.add(mList.get(index).gid);
            }
            mRecyclerView.outOfCustomChoiceMode();
        }
        mList = next;
        int maxPage = isCanPagination() && next.size() >= getPaginationSize()
                ? Math.max(1, (next.size() + getPageSize() - 1) / getPageSize()) : 1;
        mPaginationController.indexPage = Math.min(mPaginationController.indexPage, maxPage);
        updateTitle();
        mPaginationController.updatePaginationIndicator();
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        if (choice && reordered && !selected.isEmpty() && mRecyclerView != null) {
            int start = positionInList(0);
            int end = isCanPagination() && next.size() >= getPaginationSize()
                    ? Math.min(next.size(), start + getPageSize()) : next.size();
            for (int i = start; i < end; i++) if (selected.contains(next.get(i).gid)) {
                if (!mRecyclerView.isInCustomChoice()) mRecyclerView.intoCustomChoiceMode();
                mRecyclerView.toggleItemChecked(i - start);
            }
        }
        updateView();
    }

    private String groupTitle() {
        if ("@all".equals(mGroupSelection)) return getString(R.string.download_group_all);
        if ("@unknown".equals(mGroupSelection)) return getString(R.string.unknown);
        if ("custom".equals(mGrouping)) return mLabel == null
                ? getString(R.string.default_download_label_name) : mLabel;
        int colon = mGroupSelection.indexOf(':');
        return colon >= 0 ? new DownloadTags.Tag(mGroupSelection.substring(0, colon),
                mGroupSelection.substring(colon + 1)).getDisplayName(getEHContext()) : mGroupSelection;
    }

    @SuppressLint("StringFormatMatches")
    private void updateTitle() {
        try {
            setTitle(getString(R.string.scene_download_title_new,
                    groupTitle(),
                    Integer.toString(mList == null ? 0 : mList.size())));
        } catch (Exception e) {
            Analytics.recordException(e);
            setTitle(getString(R.string.scene_download_title_new,
                    groupTitle()));
        }
    }

    private void onInit() {
        if (!handleArguments(getArguments())) {
            mLabel = Settings.getRecentDownloadLabel();
            updateForLabel();
        }
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mLabel = savedInstanceState.getString(KEY_LABEL);
        updateForLabel();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_LABEL, mLabel);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mThumbnailMode = false;
        View view = inflater.inflate(R.layout.scene_download, container, false);

        Context context = getEHContext();
        assert context != null;

        setupCategorySpinner(view, context);

        mProgressView = (ProgressView) ViewUtils.$$(view, R.id.download_progress_view);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (MyEasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        if (mPaginationController.getPaginationIndicator() != null) {
            mPaginationController.setNeedInitPage(true);
        }
        PaginationIndicator paginationIndicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);
        mPaginationController.setPaginationIndicator(paginationIndicator);

        paginationIndicator.setPerPageCountChoices(mPaginationController.perPageCountChoices,
                mPaginationController.getPageSizePos(mPaginationController.pageSize));

        mViewTransition = new ViewTransition(content, tip);

        Resources resources = context.getResources();

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);

        setupRecyclerView(context, resources);
        setupFastScroller(fastScroller, context);
        setupFabLayout(context, resources);

        updateView();

        mGuideHelper.guide();
        mPaginationController.updatePaginationIndicator();
        mMetadataLoader = new com.hippo.ehviewer.download.DownloadMetadataLoader(context, () -> {
            if (mAdapter != null && isAdded()) onLocalTagsChanged();
        });
        mMetadataLoader.start(new ArrayList<>(mDownloadManager.getAllDownloadInfoList()));
        return view;
    }

    private void setupCategorySpinner(@NonNull View view, @NonNull Context context) {
        mCategorySpinner = (Spinner) ViewUtils.$$(view, R.id.category_spinner);
        // Initialize category spinner
        List<String> categoryList = new ArrayList<>();
        categoryList.add(getString(R.string.category_all)); // Add "All" option
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.DOUJINSHI)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MANGA)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ARTIST_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.GAME_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.WESTERN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.NON_H)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.IMAGE_SET)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.COSPLAY)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ASIAN_PORN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MISC)).toUpperCase(Locale.ROOT));
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, categoryList);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapter);
        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedCategory;
                switch (position) {
                    case 0:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                    case 1:
                        selectedCategory = EhConfig.DOUJINSHI;
                        break;
                    case 2:
                        selectedCategory = EhConfig.MANGA;
                        break;
                    case 3:
                        selectedCategory = EhConfig.ARTIST_CG;
                        break;
                    case 4:
                        selectedCategory = EhConfig.GAME_CG;
                        break;
                    case 5:
                        selectedCategory = EhConfig.WESTERN;
                        break;
                    case 6:
                        selectedCategory = EhConfig.NON_H;
                        break;
                    case 7:
                        selectedCategory = EhConfig.IMAGE_SET;
                        break;
                    case 8:
                        selectedCategory = EhConfig.COSPLAY;
                        break;
                    case 9:
                        selectedCategory = EhConfig.ASIAN_PORN;
                        break;
                    case 10:
                        selectedCategory = EhConfig.MISC;
                        break;
                    default:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                }
                if (selectedCategory != mSelectedCategory) {
                    mSelectedCategory = selectedCategory;
                    filterByCategory();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        // Set default selection
        mCategorySpinner.setSelection(0);
    }

    private void setupRecyclerView(@NonNull Context context, @NonNull Resources resources) {
        // 初始化拖拽管理器
        mDragDropManager = new RecyclerViewDragDropManager();
        try {
            mDragDropManager.setDraggingItemShadowDrawable(
                    (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drag shadow: " + e.getMessage());
        }


        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        mAdapter = mDragDropManager.createWrappedAdapter(mOriginalAdapter); // 包装适配器以支持拖拽
        mDragDropManager.setCheckCanDropEnabled(false);
        mRecyclerView.setAdapter(mAdapter);

        // 初始化分页监听器
        mPaginationController.bindPageChangeListener(mOriginalAdapter, mRecyclerView);
        mLayoutManager = new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL);
        mLayoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);

        // 设置拖拽动画器
        final GeneralItemAnimator animator = new DraggableItemAnimator();
        mRecyclerView.setItemAnimator(animator);

        mRecyclerView.setItemViewCacheSize(100);
        try {
            mRecyclerView.setDrawingCacheEnabled(true);
            mRecyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drawing cache: " + e.getMessage());
        }
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme), new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener(this));
//        mRecyclerView.setOnGenericMotionListener(this::onGenericMotion);
        // Cancel change animation
        RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
        if (itemAnimator instanceof GeneralItemAnimator) {
            ((GeneralItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        // 将拖拽管理器附加到RecyclerView
        if (mDragDropManager != null) {
            try {
                mDragDropManager.attachRecyclerView(mRecyclerView);
            } catch (Exception e) {
                // 忽略硬件位图相关错误
                android.util.Log.w("DownloadsScene", "Error attaching drag manager: " + e.getMessage());
            }
        }

        if (mInitPosition >= 0 && mPaginationController.indexPage != 1) {
            mPaginationController.initPage(mInitPosition);
            mRecyclerView.scrollToPosition(listIndexInPage(mInitPosition));
            mInitPosition = -1;
        }
    }

    private void setupFastScroller(@NonNull FastScroller fastScroller, @NonNull Context context) {
        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);
        fastScroller.setOnDragHandlerListener(this);
    }

    private void setupFabLayout(@NonNull Context context, @NonNull Resources resources) {
        mFabLayout.setExpanded(false, true);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setAutoCancel(false);
        mFabLayout.setOnClickFabListener(this);
        mFabLayout.setOnExpandListener(this);
        mActionFabDrawable = new AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null));
        mFabLayout.getPrimaryFab().setImageDrawable(mActionFabDrawable);
        FloatingActionButton fab = mFabLayout.getSecondaryFabAt(6);
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
        addAboveSnackView(mFabLayout);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitle();
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        if (mRefreshDialog != null) {
            mRefreshDialog.stop();
            mRefreshDialog = null;
        }
        if (mMetadataLoader != null) {
            mMetadataLoader.stop();
            mMetadataLoader = null;
        }
        super.onDestroyView();

        mGuideHelper.destroy();
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        if (null != mFabLayout) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }

        mRecyclerView = null;
        mViewTransition = null;
        mAdapter = null;
        mOriginalAdapter = null;
        mLayoutManager = null;
        mDragDropManager = null;
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_download;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        // Skip when in choice mode
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView || mRecyclerView.isInCustomChoice()) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.download_view_details:
            case R.id.download_view_thumbnails: {
                mThumbnailMode = id == R.id.download_view_thumbnails;
                item.setChecked(true);
                mLayoutManager.setColumnSize(getResources().getDimensionPixelOffset(
                        mThumbnailMode ? Settings.getThumbSizeResId() : Settings.getDetailSizeResId()));
                if (mDragDropManager != null) mDragDropManager.cancelDrag();
                if (mOriginalAdapter != null) mOriginalAdapter.notifyDataSetChanged();
                return true;
            }
            case R.id.action_start_all: {
                Intent intent = new Intent(activity, DownloadService.class);
                intent.setAction(DownloadService.ACTION_START_ALL);
                activity.startService(intent);
                return true;
            }
            case R.id.action_stop_all: {
                if (null != mDownloadManager) {
                    mDownloadManager.stopAllDownload();
                }
                return true;
            }
            case R.id.action_refresh_download_tags:
            case R.id.action_refresh_download_covers: {
                if (mDownloadManager == null) return false;
                if (mRefreshDialog != null) mRefreshDialog.stop();
                mRefreshDialog = new com.hippo.ehviewer.ui.scene.download.part.DownloadRefreshDialog(
                        activity, new ArrayList<>(mDownloadManager.getAllDownloadInfoList()),
                        id == R.id.action_refresh_download_tags
                                ? com.hippo.ehviewer.ui.scene.download.part.DownloadRefreshDialog.Mode.TAGS
                                : com.hippo.ehviewer.ui.scene.download.part.DownloadRefreshDialog.Mode.COVERS,
                        () -> {
                            updateForLabel();
                            if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
                        });
                mRefreshDialog.start();
                return true;
            }
            case R.id.action_reset_reading_progress: {
                Context context = getEHContext();
                    if (context == null) {
                    return false;
                }
                if (searching) {
                    Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                    return true;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.reset_reading_progress_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            mPaginationController.resetReadingProgressInUi();
                            if (mDownloadManager != null) {
                                Toast.makeText(context, R.string.reset_reading_progress_processing,
                                        Toast.LENGTH_SHORT).show();
                                mDownloadManager.resetAllReadingProgress(() ->
                                        Toast.makeText(context, R.string.reset_reading_progress_done,
                                                Toast.LENGTH_SHORT).show());
                            }
                        }).show();
                return true;
            }
            case R.id.search_download_gallery: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                mSearchController.gotoSearch(context, this, this);
                return true;
            }
            case R.id.all:
            case R.id.sort_by_default:
            case R.id.download_done:
            case R.id.not_started:
            case R.id.waiting:
            case R.id.downloading:
            case R.id.failed:
            case R.id.sort_by_random:
            case R.id.sort_by_pages_asc:
            case R.id.sort_by_pages_desc:
            case R.id.sort_by_gallery_id_asc:
            case R.id.sort_by_gallery_id_desc:
            case R.id.sort_by_create_time_asc:
            case R.id.sort_by_create_time_desc:
            case R.id.sort_by_rating_asc:
            case R.id.sort_by_rating_desc:
            case R.id.sort_by_name_asc:
            case R.id.sort_by_name_desc:
            case R.id.sort_by_file_size_asc:
            case R.id.sort_by_file_size_desc:
            case R.id.all_kind:
            case R.id.misc:
            case R.id.doujinshi:
            case R.id.manga:
            case R.id.artist_cg:
            case R.id.game_cg:
            case R.id.image_set:
            case R.id.cosplay:
            case R.id.asian_porn:
            case R.id.non_h:
            case R.id.western:
            case R.id.unknown:
            case R.id.local_import:
                mSearchController.gotoFilterAndSort(id);
                return true;
            case R.id.import_local_archive:
                mArchiveImporter.importLocalArchive(filePickerLauncher);
                return true;
            case R.id.import_local_album:
                mAlbumImporter.importLocalAlbum(folderPickerLauncher);
                return true;
//            case R.id.misc:
//            case R.id.doujinshi:
//            case R.id.manga:
//            case R.id.artist_cg:
//            case R.id.game_cg:
//            case R.id.image_set:
//            case R.id.cosplay:
//            case R.id.asian_porn:
//            case R.id.non_h:
//            case R.id.western:
//            case R.id.unknown:
//
//                return true;
        }
        return false;
    }

    public void updateView() {
        if (mViewTransition != null) {
            if (mList == null || mList.size() == 0) {
                mViewTransition.showView(1);
            } else {
                mViewTransition.showView(0);
            }
        }
    }

    @Override
    public View onCreateDrawerView(LayoutInflater inflater,
                                   @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (downloadLabelDraw == null) {
            downloadLabelDraw = new DownloadLabelDraw(inflater, container, this);
        }

        return downloadLabelDraw.createView();
    }

    @Override
    public void onBackPressed() {
        if (mGuideHelper.isShowing()) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    public void onEndDragHandler() {
        // Restore right drawer
        if (null != mRecyclerView && !mRecyclerView.isInCustomChoice()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
        }
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == activity || null == recyclerView) {
            return false;
        }

        if (recyclerView.isInCustomChoice()) {
            recyclerView.toggleItemChecked(position);
            return true;
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return false;
            }
            if (position < 0 || position >= list.size()) {
                return false;
            }

            DownloadInfo downloadInfo = list.get(positionInList(position));
            Intent intent = new Intent(activity, GalleryActivity.class);
            // Check if this is an imported archive
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")) {
                // This is an imported archive, ensure URI permission is available
                Uri archiveUri = Uri.parse(downloadInfo.archiveUri);
                try {
                    // Test if we can access the URI
                    try (InputStream testStream = getEHContext().getContentResolver().openInputStream(archiveUri)) {
                        if (testStream == null) {
                            Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    // Try to restore permission
                    try {
                        getEHContext().getContentResolver().takePersistableUriPermission(archiveUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ex) {
                        Toast.makeText(getEHContext(), R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
                        Analytics.recordException(ex);
                        return true;
                    }
                } catch (Exception e) {
                    Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                    return true;
                }
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(archiveUri);
            } else {
                // This is a normal download, use ACTION_EH
                intent.setAction(GalleryActivity.ACTION_EH);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            }
//            startActivity(intent);
            galleryActivityLauncher.launch(intent);
            return true;
        }
    }

    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (recyclerView == null) {
            return false;
        }

        if (!recyclerView.isInCustomChoice()) {
            recyclerView.intoCustomChoiceMode();
        }
        recyclerView.toggleItemChecked(position);

        return true;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onExpand(boolean expanded) {
        if (null == mActionFabDrawable) {
            return;
        }

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            mActionFabDrawable.setDelete(ANIMATE_TIME);
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            mActionFabDrawable.setAdd(ANIMATE_TIME);
        }
    }

    @Override
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }
        if (mRecyclerView != null && !mRecyclerView.isInCustomChoice()) {
            mRecyclerView.intoCustomChoiceMode();
            return;
        }
        view.toggle();
    }

    @Override
    public void onLocalTagsChanged() {
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        mBatchActions.onClickSecondaryFab(view, fab, position);
    }

    @Override
    public void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mMetadataLoader != null) mMetadataLoader.enqueue(java.util.Collections.singletonList(info));
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo) {
        if (mList == null) {
            return;
        }
        updateForLabel();
        updateView();

        int index = mList.indexOf(newInfo);
        if (index >= 0 && mAdapter != null) {
//            mSpiderInfoMap.put(info.gid,getSpiderInfo(info));
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
        List<DownloadInfo> infos = new ArrayList<>();
        infos.add(newInfo);
        mPaginationController.fetchSpiderInfo(infos);
    }

    @Override
    public void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList) {
        if (mProjection.hasStateFilter()) {
            rebuildDisplayedList();
        } else if (mList != null && mAdapter != null) {
            int index = mList.indexOf(info);
            if (index >= 0 && (!isCanPagination() || mList.size() < getPaginationSize()
                    || index / getPageSize() + 1 == getIndexPage()))
                mAdapter.notifyItemChanged(listIndexInPage(index));
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateAll() {
        rebuildDisplayedList();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onReload() {
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onChange() {
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onRenameLabel(String from, String to) {
        String selected = Settings.getDownloadGroupSelection("custom");
        if (("label:" + from).equals(selected)) {
            Settings.putDownloadGroupSelection("custom", "label:" + to);
        }
        if (("label:" + from).equals(mGroupSelection) && "custom".equals(mGrouping)) {
            mLabel = to;
            mGroupSelection = "label:" + to;
            Settings.putRecentDownloadLabel(to);
        }
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Override
    public void onUpdateLabels() {
        updateForLabel();
        if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
    }

    @Nullable
    public DownloadManager getMDownloadManager() {
        return mDownloadManager;
    }

    // DownloadAdapterCallback 接口实现
    @Override
    public int getIndexPage() {
        return mPaginationController.indexPage;
    }

    @Override
    public int getPageSize() {
        return mPaginationController.pageSize;
    }

    @Override
    public int getPaginationSize() {
        return mPaginationController.paginationSize;
    }

    @Override
    public boolean isCanPagination() {
        return mPaginationController.isCanPagination();
    }

    @Override
    public int positionInList(int position) {
        return mPaginationController.positionInList(position);
    }

    @Override
    public int listIndexInPage(int position) {
        return mPaginationController.listIndexInPage(position);
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mPaginationController.getSpiderInfoMap();
    }

    @Override
    public DownloadManager getDownloadManager() {
        return mDownloadManager;
    }

    @Nullable
    @Override
    public String getLabel() {
        return mLabel;
    }

    @Override
    public MyEasyRecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public RecyclerView.Adapter getNotifyAdapter() {
        return mAdapter;
    }

    @Nullable
    @Override
    public FabLayout getFabLayout() {
        return mFabLayout;
    }

    @Nullable
    @Override
    public AutoStaggeredGridLayoutManager getLayoutManager() {
        return mLayoutManager;
    }

    @Override
    public ProgressView getProgressView() {
        return mProgressView;
    }

    @Override
    public List<DownloadInfo> getBackList() {
        return mBackList;
    }

    @Override
    public DownloadSearchCallback getDownloadSearchCallback() {
        return this;
    }

    @Override
    public String getSearchKey() {
        return searchKey;
    }

    @Override
    public void setSearchKey(String searchKey) {
        this.searchKey = searchKey;
    }

    @Override
    public void setSearching(boolean searching) {
        this.searching = searching;
    }

    @Override
    public MyEasyRecyclerView.OnItemLongClickListener getItemLongClickListener() {
        return this;
    }

    @Override
    public void launchGalleryActivity(Intent intent) {
        galleryActivityLauncher.launch(intent);
    }

    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    @Override
    public void onClickTitle() {
        mSearchController.onClickTitle();
    }

    @Override
    public void onClickLeftIcon() {
        mSearchController.onClickLeftIcon();
    }

    @Override
    public void onClickRightIcon() {
        mSearchController.onClickRightIcon();
    }

    @Override
    public void onSearchEditTextClick() {
        mSearchController.onSearchEditTextClick();
    }

    @Override
    public void onApplySearch(String query) {
        mSearchController.onApplySearch(query);
    }

    protected void startSearching() {
        mSearchController.startSearching();
    }

    private void updateAdapter() {
        // 检查 Fragment 是否已附加，如果未附加则延迟创建适配器
        if (!isAdded()) {
            return;
        }
        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter;
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
    }

    @Override
    public void onSearchEditTextBackPressed() {
        mSearchController.onSearchEditTextBackPressed();
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {
        mSearchController.onStateChange(searchBar, newState, oldState, animation);
    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return mSearchController.isValidView(recyclerView);
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mSearchController.getValidRecyclerView();
    }

    @Override
    public boolean forceShowSearchBar() {
        return mSearchController.forceShowSearchBar();
    }

    @Override
    public void onDownloadSearchSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        mPaginationController.queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadListHandleSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        mPaginationController.queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadSearchFailed(List<DownloadInfo> list) {
        Toast.makeText(getEHContext(), R.string.download_searching_failed, Toast.LENGTH_LONG).show();
        mList = list;
        updateAdapter();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        mPaginationController.queryUnreadSpiderInfo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateDownloadLabels(SomethingNeedRefresh somethingNeedRefresh) {
        if (somethingNeedRefresh.isDownloadLabelDrawNeed()) {
            updateForLabel();
            if (downloadLabelDraw != null) downloadLabelDraw.updateDownloadLabels();
        }
    }

    private void filterByCategory() {
        mProjection.setCategory(mSelectedCategory);
        rebuildDisplayedList();
    }
}
