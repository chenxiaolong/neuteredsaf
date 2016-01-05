/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.noobdev.neuteredsaf;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.OperationCanceledException;
import android.os.Parcelable;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import io.noobdev.neuteredsaf.DocumentsActivity.State;
import io.noobdev.neuteredsaf.ProviderExecutor.Preemptable;
import io.noobdev.neuteredsaf.RecentsProvider.StateColumns;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat.Document;
import io.noobdev.neuteredsaf.model.DocumentInfo;
import io.noobdev.neuteredsaf.model.RootInfo;
import io.noobdev.neuteredsaf.compat.ContentProviderClientCompat;

import static io.noobdev.neuteredsaf.model.DocumentInfo.getCursorInt;
import static io.noobdev.neuteredsaf.model.DocumentInfo.getCursorLong;
import static io.noobdev.neuteredsaf.model.DocumentInfo.getCursorString;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    private View mEmptyView;
    private ListView mListView;
    private GridView mGridView;

    private AbsListView mCurrentView;

    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;

    public static final int ANIM_NONE = 1;
    public static final int ANIM_SIDE = 2;
    public static final int ANIM_DOWN = 3;
    public static final int ANIM_UP = 4;

    private int mType = TYPE_NORMAL;
    private String mStateKey;

    private int mLastMode = State.MODE_UNKNOWN;
    private int mLastSortOrder = State.SORT_ORDER_UNKNOWN;
    private boolean mLastShowSize = false;

    private boolean mHideGridTitles = false;

    private Point mThumbSize;

    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<DirectoryResult> mCallbacks;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ROOT = "root";
    private static final String EXTRA_DOC = "doc";
    private static final String EXTRA_QUERY = "query";
    private static final String EXTRA_IGNORE_STATE = "ignoreState";

    private final int mLoaderId = 42;

    public static void showNormal(FragmentManager fm, RootInfo root, DocumentInfo doc, int anim) {
        show(fm, TYPE_NORMAL, root, doc, null, anim);
    }

    public static void showSearch(FragmentManager fm, RootInfo root, String query, int anim) {
        show(fm, TYPE_SEARCH, root, null, query, anim);
    }

    private static void show(FragmentManager fm, int type, RootInfo root, DocumentInfo doc,
            String query, int anim) {
        final Bundle args = new Bundle();
        args.putInt(EXTRA_TYPE, type);
        args.putParcelable(EXTRA_ROOT, root);
        args.putParcelable(EXTRA_DOC, doc);
        args.putString(EXTRA_QUERY, query);

        final FragmentTransaction ft = fm.beginTransaction();
        switch (anim) {
            case ANIM_SIDE:
                args.putBoolean(EXTRA_IGNORE_STATE, true);
                break;
            case ANIM_DOWN:
                args.putBoolean(EXTRA_IGNORE_STATE, true);
                ft.setCustomAnimations(R.animator.dir_down, R.animator.dir_frozen);
                break;
            case ANIM_UP:
                ft.setCustomAnimations(R.animator.dir_frozen, R.animator.dir_up);
                break;
        }

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    private static String buildStateKey(RootInfo root, DocumentInfo doc) {
        return (root != null ? root.authority : "null") + ';' +
                (root != null ? root.rootId : "null") + ';' +
                (doc != null ? doc.documentId : "null");
    }

    public static DirectoryFragment get(FragmentManager fm) {
        // TODO: deal with multiple directories shown at once
        return (DirectoryFragment) fm.findFragmentById(R.id.container_directory);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();
        final Resources res = context.getResources();
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mEmptyView = view.findViewById(android.R.id.empty);

        mListView = (ListView) view.findViewById(R.id.list);
        mListView.setOnItemClickListener(mItemListener);
        mListView.setMultiChoiceModeListener(mMultiListener);
        mListView.setRecyclerListener(mRecycleListener);

        // Indent our list divider to align with text
        final Drawable divider = mListView.getDivider();
        final boolean insetLeft = res.getBoolean(R.bool.list_divider_inset_left);
        final int insetSize = res.getDimensionPixelSize(R.dimen.list_divider_inset);
        if (insetLeft) {
            mListView.setDivider(new InsetDrawable(divider, insetSize, 0, 0, 0));
        } else {
            mListView.setDivider(new InsetDrawable(divider, 0, 0, insetSize, 0));
        }

        mGridView = (GridView) view.findViewById(R.id.grid);
        mGridView.setOnItemClickListener(mItemListener);
        mGridView.setMultiChoiceModeListener(mMultiListener);
        mGridView.setRecyclerListener(mRecycleListener);

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Cancel any outstanding thumbnail requests
        final ViewGroup target = (mListView.getAdapter() != null) ? mListView : mGridView;
        final int count = target.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = target.getChildAt(i);
            mRecycleListener.onMovedToScrapHeap(view);
        }

        // Tear down any selection in progress
        mListView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
        mGridView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final State state = getDisplayState(DirectoryFragment.this);

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        mAdapter = new DocumentsAdapter();
        mType = getArguments().getInt(EXTRA_TYPE);
        mStateKey = buildStateKey(root, doc);

        mHideGridTitles = (doc != null) && doc.isGridTitlesHidden();

        mCallbacks = new LoaderCallbacks<DirectoryResult>() {
            @Override
            public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
                final String query = getArguments().getString(EXTRA_QUERY);

                Uri contentsUri;
                switch (mType) {
                    case TYPE_NORMAL:
                        contentsUri = DocumentsContractCompat.buildChildDocumentsUri(
                                doc.authority, doc.documentId);
                        return new DirectoryLoader(
                                context, mType, root, doc, contentsUri, state.userSortOrder);
                    case TYPE_SEARCH:
                        contentsUri = DocumentsContractCompat.buildSearchDocumentsUri(
                                root.authority, root.rootId, query);
                        return new DirectoryLoader(
                                context, mType, root, doc, contentsUri, state.userSortOrder);
                    default:
                        throw new IllegalStateException("Unknown type " + mType);
                }
            }

            @Override
            public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
                if (!isAdded()) return;

                mAdapter.swapResult(result);

                // Push latest state up to UI
                // TODO: if mode change was racing with us, don't overwrite it
                if (result.mode != State.MODE_UNKNOWN) {
                    state.derivedMode = result.mode;
                }
                state.derivedSortOrder = result.sortOrder;
                ((DocumentsActivity) context).onStateChanged();

                updateDisplayState();

                // When launched into empty recents, show drawer
                // TODO: CXL
                if (mAdapter.isEmpty() && !state.stackTouched) {
                    ((DocumentsActivity) context).setRootsDrawerOpen(true);
                }
                // TODO: CXL

                // Restore any previous instance state
                final SparseArray<Parcelable> container = state.dirState.remove(mStateKey);
                if (container != null && !getArguments().getBoolean(EXTRA_IGNORE_STATE, false)) {
                    getView().restoreHierarchyState(container);
                } else if (mLastSortOrder != state.derivedSortOrder) {
                    mListView.smoothScrollToPosition(0);
                    mGridView.smoothScrollToPosition(0);
                }

                mLastSortOrder = state.derivedSortOrder;
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mAdapter.swapResult(null);
            }
        };

        // Kick off loader at least once
        getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);

        updateDisplayState();
    }

    @Override
    public void onStop() {
        super.onStop();

        // Remember last scroll location
        final SparseArray<Parcelable> container = new SparseArray<>();
        getView().saveHierarchyState(container);
        final State state = getDisplayState(this);
        state.dirState.put(mStateKey, container);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDisplayState();
    }

    public void onDisplayStateChanged() {
        updateDisplayState();
    }

    public void onUserSortOrderChanged() {
        // Sort order change always triggers reload; we'll trigger state change
        // on the flip side.
        getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
    }

    public void onUserModeChanged() {
        final ContentResolver resolver = getActivity().getContentResolver();
        final State state = getDisplayState(this);

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        if (root != null && doc != null) {
            final Uri stateUri = RecentsProvider.buildState(
                    root.authority, root.rootId, doc.documentId);
            final ContentValues values = new ContentValues();
            values.put(StateColumns.MODE, state.userMode);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    resolver.insert(stateUri, values);
                    return null;
                }
            }.execute();
        }

        // Mode change is just visual change; no need to kick loader, and
        // deliver change event immediately.
        state.derivedMode = state.userMode;
        ((DocumentsActivity) getActivity()).onStateChanged();

        updateDisplayState();
    }

    private void updateDisplayState() {
        final State state = getDisplayState(this);

        if (mLastMode == state.derivedMode && mLastShowSize == state.showSize) return;
        mLastMode = state.derivedMode;
        mLastShowSize = state.showSize;

        mListView.setVisibility(state.derivedMode == State.MODE_LIST ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(state.derivedMode == State.MODE_GRID ? View.VISIBLE : View.GONE);

        final int choiceMode;
        if (state.allowMultiple) {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL;
        } else {
            choiceMode = ListView.CHOICE_MODE_NONE;
        }

        final int thumbSize;
        if (state.derivedMode == State.MODE_GRID) {
            thumbSize = getResources().getDimensionPixelSize(R.dimen.grid_width);
            mListView.setAdapter(null);
            mListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mGridView.setAdapter(mAdapter);
            mGridView.setColumnWidth(getResources().getDimensionPixelSize(R.dimen.grid_width));
            mGridView.setNumColumns(GridView.AUTO_FIT);
            mGridView.setChoiceMode(choiceMode);
            mCurrentView = mGridView;
        } else if (state.derivedMode == State.MODE_LIST) {
            thumbSize = getResources().getDimensionPixelSize(R.dimen.icon_size);
            mGridView.setAdapter(null);
            mGridView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mListView.setAdapter(mAdapter);
            mListView.setChoiceMode(choiceMode);
            mCurrentView = mListView;
        } else {
            throw new IllegalStateException("Unknown state " + state.derivedMode);
        }

        mThumbSize = new Point(thumbSize, thumbSize);
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Cursor cursor = mAdapter.getItem(position);
            if (cursor != null) {
                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                if (isDocumentEnabled(docMimeType, docFlags)) {
                    final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
                    ((DocumentsActivity) getActivity()).onDocumentPicked(doc);
                }
            }
        }
    };

    private MultiChoiceModeListener mMultiListener = new MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
            mode.setTitle(getResources()
                    .getString(R.string.mode_selected_count, mCurrentView.getCheckedItemCount()));
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            final MenuItem open = menu.findItem(R.id.menu_open);

            open.setVisible(true);

            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            final SparseBooleanArray checked = mCurrentView.getCheckedItemPositions();
            final ArrayList<DocumentInfo> docs = Lists.newArrayList();
            final int size = checked.size();
            for (int i = 0; i < size; i++) {
                if (checked.valueAt(i)) {
                    final Cursor cursor = mAdapter.getItem(checked.keyAt(i));
                    final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
                    docs.add(doc);
                }
            }

            final int id = item.getItemId();
            if (id == R.id.menu_open) {
                DocumentsActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
                mode.finish();
                return true;

            } else {
                return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // ignored
        }

        @Override
        public void onItemCheckedStateChanged(
                ActionMode mode, int position, long id, boolean checked) {
            if (checked) {
                // Directories and footer items cannot be checked
                boolean valid = false;

                final Cursor cursor = mAdapter.getItem(position);
                if (cursor != null) {
                    final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                    final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                    if (!Document.MIME_TYPE_DIR.equals(docMimeType)) {
                        valid = isDocumentEnabled(docMimeType, docFlags);
                    }
                }

                if (!valid) {
                    mCurrentView.setItemChecked(position, false);
                }
            }

            mode.setTitle(getResources()
                    .getString(R.string.mode_selected_count, mCurrentView.getCheckedItemCount()));
        }
    };

    private RecyclerListener mRecycleListener = new RecyclerListener() {
        @Override
        public void onMovedToScrapHeap(View view) {
            final ImageView iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
            if (iconThumb != null) {
                final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
                if (oldTask != null) {
                    oldTask.preempt();
                    iconThumb.setTag(null);
                }
            }
        }
    };

    private static State getDisplayState(Fragment fragment) {
        return ((DocumentsActivity) fragment.getActivity()).getDisplayState();
    }

    private static abstract class Footer {
        private final int mItemViewType;

        public Footer(int itemViewType) {
            mItemViewType = itemViewType;
        }

        public abstract View getView(View convertView, ViewGroup parent);

        public int getItemViewType() {
            return mItemViewType;
        }
    }

    private class LoadingFooter extends Footer {
        public LoadingFooter() {
            super(1);
        }

        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.derivedMode == State.MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_loading_list, parent, false);
                } else if (state.derivedMode == State.MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_loading_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            return convertView;
        }
    }

    private class MessageFooter extends Footer {
        private final int mIcon;
        private final String mMessage;

        public MessageFooter(int itemViewType, int icon, String message) {
            super(itemViewType);
            mIcon = icon;
            mMessage = message;
        }

        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.derivedMode == State.MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_message_list, parent, false);
                } else if (state.derivedMode == State.MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_message_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            icon.setImageResource(mIcon);
            title.setText(mMessage);
            return convertView;
        }
    }

    private class DocumentsAdapter extends BaseAdapter {
        private Cursor mCursor;
        private int mCursorCount;

        private List<Footer> mFooters = Lists.newArrayList();

        public void swapResult(DirectoryResult result) {
            mCursor = result != null ? result.cursor : null;
            mCursorCount = mCursor != null ? mCursor.getCount() : 0;

            mFooters.clear();

            final Bundle extras = mCursor != null ? mCursor.getExtras() : null;
            if (extras != null) {
                final String info = extras.getString(DocumentsContractCompat.EXTRA_INFO);
                if (info != null) {
                    mFooters.add(new MessageFooter(2, R.drawable.ic_dialog_info, info));
                }
                final String error = extras.getString(DocumentsContractCompat.EXTRA_ERROR);
                if (error != null) {
                    mFooters.add(new MessageFooter(3, R.drawable.ic_dialog_alert, error));
                }
                if (extras.getBoolean(DocumentsContractCompat.EXTRA_LOADING, false)) {
                    mFooters.add(new LoadingFooter());
                }
            }

            if (result != null && result.exception != null) {
                mFooters.add(new MessageFooter(
                        3, R.drawable.ic_dialog_alert, getString(R.string.query_error)));
            }

            if (isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
            }

            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position < mCursorCount) {
                return getDocumentView(position, convertView, parent);
            } else {
                position -= mCursorCount;
                convertView = mFooters.get(position).getView(convertView, parent);
                // Only the view itself is disabled; contents inside shouldn't
                // be dimmed.
                convertView.setEnabled(false);
                return convertView;
            }
        }

        private View getDocumentView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

            final RootsCache roots = DocumentsApplication.getRootsCache(context);
            final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                    context, mThumbSize);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.derivedMode == State.MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_doc_list, parent, false);
                } else if (state.derivedMode == State.MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_doc_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            final Cursor cursor = getItem(position);

            final String docAuthority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
            final String docRootId = getCursorString(cursor, RootCursorWrapper.COLUMN_ROOT_ID);
            final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
            final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
            final long docLastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
            final int docIcon = getCursorInt(cursor, Document.COLUMN_ICON);
            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            final String docSummary = getCursorString(cursor, Document.COLUMN_SUMMARY);
            final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);

            final View line1 = convertView.findViewById(R.id.line1);
            final View line2 = convertView.findViewById(R.id.line2);

            final ImageView iconMime = (ImageView) convertView.findViewById(R.id.icon_mime);
            final ImageView iconThumb = (ImageView) convertView.findViewById(R.id.icon_thumb);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final ImageView icon1 = (ImageView) convertView.findViewById(android.R.id.icon1);
            final ImageView icon2 = (ImageView) convertView.findViewById(android.R.id.icon2);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);
            final TextView date = (TextView) convertView.findViewById(R.id.date);
            final TextView size = (TextView) convertView.findViewById(R.id.size);

            final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
            if (oldTask != null) {
                oldTask.preempt();
                iconThumb.setTag(null);
            }

            iconMime.animate().cancel();
            iconThumb.animate().cancel();

            final boolean supportsThumbnail = (docFlags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
            final boolean allowThumbnail = (state.derivedMode == State.MODE_GRID)
                    || MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, docMimeType);
            final boolean showThumbnail = supportsThumbnail && allowThumbnail;

            final boolean enabled = isDocumentEnabled(docMimeType, docFlags);
            final float iconAlpha = (state.derivedMode == State.MODE_LIST && !enabled) ? 0.5f : 1f;

            boolean cacheHit = false;
            if (showThumbnail) {
                final Uri uri = DocumentsContractCompat.buildDocumentUri(docAuthority, docId);
                final Bitmap cachedResult = thumbs.get(uri);
                if (cachedResult != null) {
                    iconThumb.setImageBitmap(cachedResult);
                    cacheHit = true;
                } else {
                    iconThumb.setImageDrawable(null);
                    final ThumbnailAsyncTask task = new ThumbnailAsyncTask(
                            uri, iconMime, iconThumb, mThumbSize, iconAlpha);
                    iconThumb.setTag(task);
                    ProviderExecutor.forAuthority(docAuthority).execute(task);
                }
            }

            // Always throw MIME icon into place, even when a thumbnail is being
            // loaded in background.
            if (cacheHit) {
                iconMime.setAlpha(0f);
                iconMime.setImageDrawable(null);
                iconThumb.setAlpha(1f);
            } else {
                iconMime.setAlpha(1f);
                iconThumb.setAlpha(0f);
                iconThumb.setImageDrawable(null);
                if (docIcon != 0) {
                    iconMime.setImageDrawable(
                            IconUtils.loadPackageIcon(context, docAuthority, docIcon));
                } else {
                    iconMime.setImageDrawable(IconUtils.loadMimeIcon(
                            context, docMimeType, docAuthority, docId, state.derivedMode));
                }
            }

            boolean hasLine1 = false;
            boolean hasLine2 = false;

            final boolean hideTitle = (state.derivedMode == State.MODE_GRID) && mHideGridTitles;
            if (!hideTitle) {
                title.setText(docDisplayName);
                hasLine1 = true;
            }

            Drawable iconDrawable = null;
            // Directories showing thumbnails in grid mode get a little icon
            // hint to remind user they're a directory.
            if (Document.MIME_TYPE_DIR.equals(docMimeType) && state.derivedMode == State.MODE_GRID
                    && showThumbnail) {
                iconDrawable = IconUtils.applyTintAttr(context, R.drawable.ic_doc_folder,
                        android.R.attr.textColorPrimaryInverse);
            }

            if (summary != null) {
                if (docSummary != null) {
                    summary.setText(docSummary);
                    summary.setVisibility(View.VISIBLE);
                    hasLine2 = true;
                } else {
                    summary.setVisibility(View.INVISIBLE);
                }
            }

            if (icon1 != null) icon1.setVisibility(View.GONE);
            if (icon2 != null) icon2.setVisibility(View.GONE);

            if (iconDrawable != null) {
                if (hasLine1) {
                    icon1.setVisibility(View.VISIBLE);
                    icon1.setImageDrawable(iconDrawable);
                } else {
                    icon2.setVisibility(View.VISIBLE);
                    icon2.setImageDrawable(iconDrawable);
                }
            }

            if (docLastModified == -1) {
                date.setText(null);
            } else {
                date.setText(formatTime(context, docLastModified));
                hasLine2 = true;
            }

            if (state.showSize) {
                size.setVisibility(View.VISIBLE);
                if (Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
                    size.setText(null);
                } else {
                    size.setText(Formatter.formatFileSize(context, docSize));
                    hasLine2 = true;
                }
            } else {
                size.setVisibility(View.GONE);
            }

            if (line1 != null) {
                line1.setVisibility(hasLine1 ? View.VISIBLE : View.GONE);
            }
            if (line2 != null) {
                line2.setVisibility(hasLine2 ? View.VISIBLE : View.GONE);
            }

            setEnabledRecursive(convertView, enabled);

            iconMime.setAlpha(iconAlpha);
            iconThumb.setAlpha(iconAlpha);
            if (icon1 != null) icon1.setAlpha(iconAlpha);
            if (icon2 != null) icon2.setAlpha(iconAlpha);

            return convertView;
        }

        @Override
        public int getCount() {
            return mCursorCount + mFooters.size();
        }

        @Override
        public Cursor getItem(int position) {
            if (position < mCursorCount) {
                mCursor.moveToPosition(position);
                return mCursor;
            } else {
                return null;
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < mCursorCount) {
                return 0;
            } else {
                position -= mCursorCount;
                return mFooters.get(position).getItemViewType();
            }
        }
    }

    private static class ThumbnailAsyncTask extends AsyncTask<Uri, Void, Bitmap>
            implements Preemptable {
        private final Uri mUri;
        private final ImageView mIconMime;
        private final ImageView mIconThumb;
        private final Point mThumbSize;
        private final float mTargetAlpha;
        private Context mContext;

        public ThumbnailAsyncTask(Uri uri, ImageView iconMime, ImageView iconThumb, Point thumbSize,
                float targetAlpha) {
            mUri = uri;
            mIconMime = iconMime;
            mIconThumb = iconThumb;
            mThumbSize = thumbSize;
            mTargetAlpha = targetAlpha;
        }

        @Override
        public void preempt() {
            cancel(false);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mContext = mIconThumb.getContext();
        }

        @Override
        protected Bitmap doInBackground(Uri... params) {
            if (isCancelled()) return null;

            final ContentResolver resolver = mContext.getContentResolver();

            ContentProviderClient client = null;
            Bitmap result = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mUri.getAuthority());
                result = DocumentsContractCompat.getDocumentThumbnail(client, mUri, mThumbSize);
                if (result != null) {
                    final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                            mContext, mThumbSize);
                    thumbs.put(mUri, result);
                }
            } catch (Exception e) {
                if (!(e instanceof OperationCanceledException)) {
                    Log.w(DocumentsActivity.TAG, "Failed to load thumbnail for " + mUri + ": " + e);
                }
            } finally {
                ContentProviderClientCompat.releaseQuietly(client);
            }
            return result;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (mIconThumb.getTag() == this && result != null) {
                mIconThumb.setTag(null);
                mIconThumb.setImageBitmap(result);

                mIconMime.setAlpha(mTargetAlpha);
                mIconMime.animate().alpha(0f).start();
                mIconThumb.setAlpha(0f);
                mIconThumb.animate().alpha(mTargetAlpha).start();
            }

            mContext = null;
        }
    }

    private static String formatTime(Context context, long when) {
        // TODO: DateUtils should make this easier
        Calendar then = new GregorianCalendar();
        then.setTime(new Date(when));
        Calendar now = new GregorianCalendar();

        int flags = DateUtils.FORMAT_NO_NOON | DateUtils.FORMAT_NO_MIDNIGHT
                | DateUtils.FORMAT_ABBREV_ALL;

        if (then.get(Calendar.YEAR) != now.get(Calendar.YEAR)) {
            flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
        } else if (then.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)) {
            flags |= DateUtils.FORMAT_SHOW_DATE;
        } else {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        }

        return DateUtils.formatDateTime(context, when, flags);
    }

    private void setEnabledRecursive(View v, boolean enabled) {
        if (v == null) return;
        if (v.isEnabled() == enabled) return;
        v.setEnabled(enabled);

        if (v instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                setEnabledRecursive(vg.getChildAt(i), enabled);
            }
        }
    }

    private boolean isDocumentEnabled(String docMimeType, int docFlags) {
        final State state = getDisplayState(DirectoryFragment.this);

        // Directories are always enabled
        if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
            return true;
        }

        // Read-only files are disabled when creating
        if (state.action == State.ACTION_CREATE && (docFlags & Document.FLAG_SUPPORTS_WRITE) == 0) {
            return false;
        }

        return MimePredicate.mimeMatches(state.acceptMimes, docMimeType);
    }
}
