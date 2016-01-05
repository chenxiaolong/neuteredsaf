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

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.MenuItemCompat.OnActionExpandListener;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SearchView.OnQueryTextListener;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.collect.Maps;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

import io.noobdev.neuteredsaf.RecentsProvider.ResumeColumns;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat.Root;
import io.noobdev.neuteredsaf.model.DocumentInfo;
import io.noobdev.neuteredsaf.model.DocumentStack;
import io.noobdev.neuteredsaf.model.DurableUtils;
import io.noobdev.neuteredsaf.model.RootInfo;
import io.noobdev.neuteredsaf.providers.ProviderConstants;
import io.noobdev.neuteredsaf.compat.ContentProviderClientCompat;
import io.noobdev.neuteredsaf.compat.IOUtils;

import static io.noobdev.neuteredsaf.DirectoryFragment.ANIM_DOWN;
import static io.noobdev.neuteredsaf.DirectoryFragment.ANIM_NONE;
import static io.noobdev.neuteredsaf.DirectoryFragment.ANIM_SIDE;
import static io.noobdev.neuteredsaf.DirectoryFragment.ANIM_UP;
import static io.noobdev.neuteredsaf.DocumentsActivity.State.ACTION_CREATE;
import static io.noobdev.neuteredsaf.DocumentsActivity.State.ACTION_OPEN;
import static io.noobdev.neuteredsaf.DocumentsActivity.State.MODE_GRID;
import static io.noobdev.neuteredsaf.DocumentsActivity.State.MODE_LIST;

public class DocumentsActivity extends AppCompatActivity {
    public static final String TAG = "Documents";

    private static final String EXTRA_STATE = "state";

    private boolean mShowAsDialog;

    private SearchView mSearchView;

    private Toolbar mToolbar;
    private Spinner mToolbarStack;

    private Toolbar mRootsToolbar;

    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private View mRootsDrawer;

    private boolean mIgnoreNextNavigation;
    private boolean mIgnoreNextClose;
    private boolean mIgnoreNextCollapse;

    private boolean mSearchExpanded;

    private RootsCache mRoots;
    private State mState;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mRoots = DocumentsApplication.getRootsCache(this);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        final Context context = this;
        final Resources res = getResources();
        mShowAsDialog = res.getBoolean(R.bool.show_as_dialog);

        if (mShowAsDialog) {
            // Strongly define our horizontal dimension; we leave vertical as
            // WRAP_CONTENT so that system resizes us when IME is showing.
            final WindowManager.LayoutParams a = getWindow().getAttributes();

            final Point size = new Point();
            getWindowManager().getDefaultDisplay().getSize(size);
            a.width = (int) res.getFraction(R.fraction.dialog_width, size.x, size.x);

            getWindow().setAttributes(a);

        } else {
            // Non-dialog means we have a drawer
            mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

            mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                    R.string.drawer_open, R.string.drawer_close);

            mDrawerLayout.setDrawerListener(mDrawerListener);

            mRootsDrawer = findViewById(R.id.drawer_roots);
        }

        if (icicle != null) {
            mState = icicle.getParcelable(EXTRA_STATE);
        } else {
            buildDefaultState();
        }

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mToolbar.setTitleTextAppearance(context,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);

        mToolbarStack = (Spinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);

        mRootsToolbar = (Toolbar) findViewById(R.id.roots_toolbar);
        if (mRootsToolbar != null) {
            mRootsToolbar.setTitleTextAppearance(context,
                    android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);
        }

        setSupportActionBar(mToolbar);

        if (mState.action == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }

        if (mState.action == ACTION_OPEN || mState.action == ACTION_CREATE) {
            RootsFragment.show(getFragmentManager());
        }

        if (!mState.restored) {
            new RestoreStackTask().execute();
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    private void buildDefaultState() {
        mState = new State();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (ProviderConstants.ACTION_OPEN_DOCUMENT.equals(action)) {
            mState.action = ACTION_OPEN;
        } else if (ProviderConstants.ACTION_CREATE_DOCUMENT.equals(action)) {
            mState.action = ACTION_CREATE;
        }

        if (mState.action == ACTION_OPEN) {
            mState.allowMultiple = intent.getBooleanExtra(
                    ProviderConstants.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (intent.hasExtra(ProviderConstants.EXTRA_MIME_TYPES)) {
            mState.acceptMimes = intent.getStringArrayExtra(ProviderConstants.EXTRA_MIME_TYPES);
        } else {
            mState.acceptMimes = new String[] { intent.getType() };
        }

        mState.showSize = true;
    }

    private class RestoreStackTask extends AsyncTask<Void, Void, Void> {
        private volatile boolean mRestoredStack;

        @Override
        protected Void doInBackground(Void... params) {
            // Restore last stack for calling package
            final String packageName = getCallingPackageMaybeExtra();
            final Cursor cursor = getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, mState.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to resume: " + e);
            } finally {
                IOUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = mRoots.getMatchingRootsBlocking(mState);
                try {
                    mState.stack.updateRoot(matchingRoots);
                    mState.stack.updateDocuments(getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to restore stack: " + e);
                    mState.stack.reset();
                    mRestoredStack = false;
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (isDestroyed()) return;
            mState.restored = true;

            // Show drawer when no stack restored, but only when requesting
            // non-visual content. However, if we last used an external app,
            // drawer is always shown.

            boolean showDrawer = false;
            if (!mRestoredStack) {
                showDrawer = true;
            }
            if (MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, mState.acceptMimes)) {
                showDrawer = false;
            }

            if (showDrawer) {
                setRootsDrawerOpen(true);
            }

            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    private DrawerListener mDrawerListener = new DrawerListener() {
        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            mDrawerToggle.onDrawerSlide(drawerView, slideOffset);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            mDrawerToggle.onDrawerOpened(drawerView);
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            mDrawerToggle.onDrawerClosed(drawerView);
        }

        @Override
        public void onDrawerStateChanged(int newState) {
            mDrawerToggle.onDrawerStateChanged(newState);
        }
    };

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (mDrawerToggle != null) {
            mDrawerToggle.syncState();
        }
        updateActionBar();
    }

    public void setRootsDrawerOpen(boolean open) {
        if (!mShowAsDialog) {
            if (open) {
                mDrawerLayout.openDrawer(mRootsDrawer);
            } else {
                mDrawerLayout.closeDrawer(mRootsDrawer);
            }
        }
    }

    private boolean isRootsDrawerOpen() {
        if (mShowAsDialog) {
            return false;
        } else {
            return mDrawerLayout.isDrawerOpen(mRootsDrawer);
        }
    }

    public void updateActionBar() {
        if (mRootsToolbar != null) {
            if (mState.action == ACTION_OPEN) {
                mRootsToolbar.setTitle(R.string.title_open);
            } else if (mState.action == ACTION_CREATE) {
                mRootsToolbar.setTitle(R.string.title_save);
            }
        }

        final RootInfo root = getCurrentRoot();
        final boolean showRootIcon = mShowAsDialog;
        if (showRootIcon) {
            mToolbar.setNavigationIcon(
                    root != null ? root.loadToolbarIcon(mToolbar.getContext()) : null);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(null);
        } else {
            mToolbar.setNavigationIcon(R.drawable.ic_hamburger);
            mToolbar.setNavigationContentDescription(R.string.drawer_open);
            mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRootsDrawerOpen(true);
                }
            });
        }

        if (mSearchExpanded) {
            mToolbar.setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else {
            if (mState.stack.size() <= 1) {
                // TODO: CXL
                //mToolbar.setTitle(root.title);
                mToolbar.setTitle(root != null ? root.title : null);
                // TODO: CXL
                mToolbarStack.setVisibility(View.GONE);
                mToolbarStack.setAdapter(null);
            } else {
                mToolbar.setTitle(null);
                mToolbarStack.setVisibility(View.VISIBLE);
                mToolbarStack.setAdapter(mStackAdapter);

                mIgnoreNextNavigation = true;
                mToolbarStack.setSelection(mStackAdapter.getCount() - 1);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity, menu);

        // Most actions are visible when showing as dialog
        if (mShowAsDialog) {
            for (int i = 0; i < menu.size(); i++) {
                final MenuItem item = menu.getItem(i);
                switch (item.getItemId()) {
                    default:
                        MenuItemCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_ALWAYS);
                }
            }
        }

        final MenuItem searchMenu = menu.findItem(R.id.menu_search);
        mSearchView = (SearchView) MenuItemCompat.getActionView(searchMenu);
        mSearchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mSearchExpanded = true;
                mState.currentSearch = query;
                mSearchView.clearFocus();
                onCurrentDirectoryChanged(ANIM_NONE);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        MenuItemCompat.setOnActionExpandListener(searchMenu, new OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchExpanded = true;
                updateActionBar();
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchExpanded = false;
                if (mIgnoreNextCollapse) {
                    mIgnoreNextCollapse = false;
                    return true;
                }

                mState.currentSearch = null;
                onCurrentDirectoryChanged(ANIM_NONE);
                return true;
            }
        });

        mSearchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                mSearchExpanded = false;
                if (mIgnoreNextClose) {
                    mIgnoreNextClose = false;
                    return false;
                }

                mState.currentSearch = null;
                onCurrentDirectoryChanged(ANIM_NONE);
                return false;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final FragmentManager fm = getFragmentManager();

        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem search = menu.findItem(R.id.menu_search);
        final MenuItem sort = menu.findItem(R.id.menu_sort);
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);

        sort.setVisible(cwd != null);
        grid.setVisible(mState.derivedMode != MODE_GRID);
        list.setVisible(mState.derivedMode != MODE_LIST);

        if (mState.currentSearch != null) {
            // Search uses backend ranking; no sorting
            sort.setVisible(false);

            search.expandActionView();

            mSearchView.setIconified(false);
            mSearchView.clearFocus();
            mSearchView.setQuery(mState.currentSearch, false);
        } else {
            mIgnoreNextClose = true;
            mSearchView.setIconified(true);
            mSearchView.clearFocus();

            mIgnoreNextCollapse = true;
            search.collapseActionView();
        }

        // Only sort by size when visible
        sortSize.setVisible(mState.showSize);

        boolean searchVisible;
        if (mState.action == ACTION_CREATE) {
            createDir.setVisible(cwd != null && cwd.isCreateSupported());
            searchVisible = false;

            // No display options in recent directories
            if (cwd == null) {
                grid.setVisible(false);
                list.setVisible(false);
            }

            if (mState.action == ACTION_CREATE) {
                SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());
            }
        } else {
            createDir.setVisible(false);

            searchVisible = root != null
                    && ((root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0);
        }

        // TODO: close any search in-progress when hiding
        search.setVisible(searchVisible);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle != null && mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_create_dir) {
            CreateDirectoryFragment.show(getFragmentManager());
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        } else if (id == R.id.menu_sort_name) {
            setUserSortOrder(State.SORT_ORDER_DISPLAY_NAME);
            return true;
        } else if (id == R.id.menu_sort_date) {
            setUserSortOrder(State.SORT_ORDER_LAST_MODIFIED);
            return true;
        } else if (id == R.id.menu_sort_size) {
            setUserSortOrder(State.SORT_ORDER_SIZE);
            return true;
        } else if (id == R.id.menu_grid) {
            setUserMode(State.MODE_GRID);
            return true;
        } else if (id == R.id.menu_list) {
            setUserMode(State.MODE_LIST);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Update UI to reflect internal state changes not from user.
     */
    public void onStateChanged() {
        invalidateOptionsMenu();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    private void setUserSortOrder(int sortOrder) {
        mState.userSortOrder = sortOrder;
        DirectoryFragment.get(getFragmentManager()).onUserSortOrderChanged();
    }

    /**
     * Set state mode based on explicit user action.
     */
    private void setUserMode(int mode) {
        mState.userMode = mode;
        DirectoryFragment.get(getFragmentManager()).onUserModeChanged();
    }

    public void setPending(boolean pending) {
        final SaveFragment save = SaveFragment.get(getFragmentManager());
        if (save != null) {
            save.setPending(pending);
        }
    }

    @Override
    public void onBackPressed() {
        if (!mState.stackTouched) {
            super.onBackPressed();
            return;
        }

        final int size = mState.stack.size();
        if (size > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged(ANIM_UP);
        } else if (size == 1 && !isRootsDrawerOpen()) {
            // TODO: open root drawer once we can capture back key
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(EXTRA_STATE, mState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
    }

    private BaseAdapter mStackAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir, parent, false);
            }

            final ImageView subdir = (ImageView) convertView.findViewById(R.id.subdir);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
                subdir.setVisibility(View.GONE);
            } else {
                title.setText(doc.displayName);
                subdir.setVisibility(View.VISIBLE);
            }

            return convertView;
        }
    };

    private OnItemSelectedListener mStackListener = new OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (mIgnoreNextNavigation) {
                mIgnoreNextNavigation = false;
                return;
            }

            while (mState.stack.size() > position + 1) {
                mState.stackTouched = true;
                mState.stack.pop();
            }
            onCurrentDirectoryChanged(ANIM_UP);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Ignored
        }
    };

    public RootInfo getCurrentRoot() {
        if (mState.stack.root != null) {
            return mState.stack.root;
        } else {
            return mRoots.getDefaultRoot();
        }
    }

    public DocumentInfo getCurrentDirectory() {
        return mState.stack.peek();
    }

    private String getCallingPackageMaybeExtra() {
        final String extra = getIntent().getStringExtra(DocumentsContractCompat.EXTRA_PACKAGE_NAME);
        return (extra != null) ? extra : getCallingPackage();
    }

    public Executor getCurrentExecutor() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (cwd != null && cwd.authority != null) {
            return ProviderExecutor.forAuthority(cwd.authority);
        } else {
            return AsyncTask.THREAD_POOL_EXECUTOR;
        }
    }

    public State getDisplayState() {
        return mState;
    }

    private void onCurrentDirectoryChanged(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (cwd == null) {
            // Open default root if nothing is selected
            onRootPicked(getCurrentRoot(), false);
        } else {
            if (mState.currentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, mState.currentSearch, anim);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd, anim);
            }
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        final RootsFragment roots = RootsFragment.get(fm);
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        updateActionBar();
        invalidateOptionsMenu();
        dumpStack();
    }

    public void onStackPicked(DocumentStack stack) {
        try {
            // Update the restored stack to ensure we have freshest data
            stack.updateDocuments(getContentResolver());

            mState.stack = stack;
            mState.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_SIDE);

        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed to restore stack: " + e);
        }
    }

    public void onRootPicked(RootInfo root, boolean closeDrawer) {
        // Clear entire backstack and start in new root
        mState.stack.root = root;
        mState.stack.clear();
        mState.stackTouched = true;

        new PickRootTask(root).executeOnExecutor(getCurrentExecutor());

        if (closeDrawer) {
            setRootsDrawerOpen(false);
        }
    }

    private class PickRootTask extends AsyncTask<Void, Void, DocumentInfo> {
        private RootInfo mRoot;

        public PickRootTask(RootInfo root) {
            mRoot = root;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            try {
                final Uri uri = DocumentsContractCompat.buildDocumentUri(
                        mRoot.authority, mRoot.documentId);
                return DocumentInfo.fromUri(getContentResolver(), uri);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to find root", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (result != null) {
                mState.stack.push(result);
                mState.stackTouched = true;
                onCurrentDirectoryChanged(ANIM_SIDE);
            }
        }
    }

    public void onDocumentPicked(DocumentInfo doc) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory()) {
            mState.stack.push(doc);
            mState.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_DOWN);
        } else if (mState.action == ACTION_OPEN) {
            // Explicit file picked, return
            new ExistingFinishTask(doc.derivedUri).executeOnExecutor(getCurrentExecutor());
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        }
    }

    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            new ExistingFinishTask(uris).executeOnExecutor(getCurrentExecutor());
        }
    }

    public void onSaveRequested(DocumentInfo replaceTarget) {
        new ExistingFinishTask(replaceTarget.derivedUri).executeOnExecutor(getCurrentExecutor());
    }

    public void onSaveRequested(String mimeType, String displayName) {
        new CreateFinishTask(mimeType, displayName).executeOnExecutor(getCurrentExecutor());
    }

    private void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    private class CreateFinishTask extends AsyncTask<Void, Void, Uri> {
        private final String mMimeType;
        private final String mDisplayName;

        public CreateFinishTask(String mimeType, String displayName) {
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void onPreExecute() {
            setPending(true);
        }

        @Override
        protected Uri doInBackground(Void... params) {
            final ContentResolver resolver = getContentResolver();
            final DocumentInfo cwd = getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContractCompat.createDocument(
                        client, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
            } finally {
                ContentProviderClientCompat.releaseQuietly(client);
            }

            if (childUri != null) {
                saveStackBlocking();
            }

            return childUri;
        }

        @Override
        protected void onPostExecute(Uri result) {
            if (result != null) {
                onFinished(result);
            } else {
                Toast.makeText(DocumentsActivity.this, R.string.save_error, Toast.LENGTH_SHORT)
                        .show();
            }

            setPending(false);
        }
    }

    private class ExistingFinishTask extends AsyncTask<Void, Void, Void> {
        private final Uri[] mUris;

        public ExistingFinishTask(Uri... uris) {
            mUris = uris;
        }

        @Override
        protected Void doInBackground(Void... params) {
            saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            onFinished(mUris);
        }
    }

    public static class State implements android.os.Parcelable {
        public int action;
        public String[] acceptMimes;

        /** Explicit user choice */
        public int userMode = MODE_UNKNOWN;
        /** Derived after loader */
        public int derivedMode = MODE_LIST;

        /** Explicit user choice */
        public int userSortOrder = SORT_ORDER_UNKNOWN;
        /** Derived after loader */
        public int derivedSortOrder = SORT_ORDER_DISPLAY_NAME;

        public boolean allowMultiple = false;
        public boolean showSize = false;
        public boolean stackTouched = false;
        public boolean restored = false;

        /** Current user navigation stack; empty implies recents. */
        public DocumentStack stack = new DocumentStack();
        /** Currently active search, overriding any stack. */
        public String currentSearch;

        /** Instance state for every shown directory */
        public HashMap<String, SparseArray<Parcelable>> dirState = Maps.newHashMap();

        public static final int ACTION_OPEN = 1;
        public static final int ACTION_CREATE = 2;

        public static final int MODE_UNKNOWN = 0;
        public static final int MODE_LIST = 1;
        public static final int MODE_GRID = 2;

        public static final int SORT_ORDER_UNKNOWN = 0;
        public static final int SORT_ORDER_DISPLAY_NAME = 1;
        public static final int SORT_ORDER_LAST_MODIFIED = 2;
        public static final int SORT_ORDER_SIZE = 3;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(action);
            out.writeInt(userMode);
            out.writeStringArray(acceptMimes);
            out.writeInt(userSortOrder);
            out.writeInt(allowMultiple ? 1 : 0);
            out.writeInt(showSize ? 1 : 0);
            out.writeInt(stackTouched ? 1 : 0);
            out.writeInt(restored ? 1 : 0);
            DurableUtils.writeToParcel(out, stack);
            out.writeString(currentSearch);
            out.writeMap(dirState);
        }

        public static final Creator<State> CREATOR = new Creator<State>() {
            @Override
            public State createFromParcel(Parcel in) {
                final State state = new State();
                state.action = in.readInt();
                state.userMode = in.readInt();
                state.acceptMimes = in.createStringArray();
                state.userSortOrder = in.readInt();
                state.allowMultiple = in.readInt() != 0;
                state.showSize = in.readInt() != 0;
                state.stackTouched = in.readInt() != 0;
                state.restored = in.readInt() != 0;
                DurableUtils.readFromParcel(in, state.stack);
                state.currentSearch = in.readString();
                in.readMap(state.dirState, null);
                return state;
            }

            @Override
            public State[] newArray(int size) {
                return new State[size];
            }
        };
    }

    private void dumpStack() {
        Log.d(TAG, "Current stack: ");
        Log.d(TAG, " * " + mState.stack.root);
        for (DocumentInfo doc : mState.stack) {
            Log.d(TAG, " +-- " + doc);
        }
    }

    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }
}
