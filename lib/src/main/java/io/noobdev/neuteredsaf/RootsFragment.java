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
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.google.common.collect.Lists;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.noobdev.neuteredsaf.DocumentsActivity.State;
import io.noobdev.neuteredsaf.model.DocumentInfo;
import io.noobdev.neuteredsaf.model.RootInfo;
import io.noobdev.neuteredsaf.compat.ObjectsCompat;

/**
 * Display list of known storage backend roots.
 */
public class RootsFragment extends Fragment {

    private ListView mList;
    private RootsAdapter mAdapter;

    private LoaderCallbacks<Collection<RootInfo>> mCallbacks;

    public static void show(FragmentManager fm) {
        final RootsFragment fragment = new RootsFragment();

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_roots, fragment);
        ft.commitAllowingStateLoss();
    }

    public static RootsFragment get(FragmentManager fm) {
        return (RootsFragment) fm.findFragmentById(R.id.container_roots);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_roots, container, false);
        mList = (ListView) view.findViewById(android.R.id.list);
        mList.setOnItemClickListener(mItemListener);
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final RootsCache roots = DocumentsApplication.getRootsCache(context);
        final State state = ((DocumentsActivity) context).getDisplayState();

        mCallbacks = new LoaderCallbacks<Collection<RootInfo>>() {
            @Override
            public Loader<Collection<RootInfo>> onCreateLoader(int id, Bundle args) {
                return new RootsLoader(context, roots, state);
            }

            @Override
            public void onLoadFinished(
                    Loader<Collection<RootInfo>> loader, Collection<RootInfo> result) {
                if (!isAdded()) return;

                mAdapter = new RootsAdapter(context, result);
                mList.setAdapter(mAdapter);

                onCurrentRootChanged();
            }

            @Override
            public void onLoaderReset(Loader<Collection<RootInfo>> loader) {
                mAdapter = null;
                mList.setAdapter(null);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        onDisplayStateChanged();
    }

    public void onDisplayStateChanged() {
        mList.setOnItemLongClickListener(null);
        mList.setLongClickable(false);

        getLoaderManager().restartLoader(2, null, mCallbacks);
    }

    public void onCurrentRootChanged() {
        if (mAdapter == null) return;

        final RootInfo root = ((DocumentsActivity) getActivity()).getCurrentRoot();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final Object item = mAdapter.getItem(i);
            if (item instanceof RootItem) {
                final RootInfo testRoot = ((RootItem) item).root;
                if (ObjectsCompat.equals(testRoot, root)) {
                    mList.setItemChecked(i, true);
                    return;
                }
            }
        }
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final DocumentsActivity activity = DocumentsActivity.get(RootsFragment.this);
            final Item item = mAdapter.getItem(position);
            if (item instanceof RootItem) {
                activity.onRootPicked(((RootItem) item).root, true);
            } else {
                throw new IllegalStateException("Unknown root: " + item);
            }
        }
    };

    private static abstract class Item {
        private final int mLayoutId;

        public Item(int layoutId) {
            mLayoutId = layoutId;
        }

        public View getView(View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(mLayoutId, parent, false);
            }
            bindView(convertView);
            return convertView;
        }

        public abstract void bindView(View convertView);
    }

    private static class RootItem extends Item {
        public final RootInfo root;

        public RootItem(RootInfo root) {
            super(R.layout.item_root);
            this.root = root;
        }

        @Override
        public void bindView(View convertView) {
            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final Context context = convertView.getContext();
            icon.setImageDrawable(root.loadDrawerIcon(context));
            title.setText(root.title);

            // Show available space if no summary
            String summaryText = root.summary;
            if (TextUtils.isEmpty(summaryText) && root.availableBytes >= 0) {
                summaryText = context.getString(R.string.root_available_bytes,
                        Formatter.formatFileSize(context, root.availableBytes));
            }

            summary.setText(summaryText);
            summary.setVisibility(TextUtils.isEmpty(summaryText) ? View.GONE : View.VISIBLE);
        }
    }

    private static class RootsAdapter extends ArrayAdapter<Item> {
        public RootsAdapter(Context context, Collection<RootInfo> roots) {
            super(context, 0);

            final List<RootInfo> clouds = Lists.newArrayList();
            final List<RootInfo> locals = Lists.newArrayList();

            for (RootInfo root : roots) {
                if (root.isExternalStorage()) {
                    locals.add(root);
                } else {
                    clouds.add(root);
                }
            }

            final RootComparator comp = new RootComparator();
            Collections.sort(clouds, comp);
            Collections.sort(locals, comp);

            for (RootInfo cloud : clouds) {
                add(new RootItem(cloud));
            }

            for (RootInfo local : locals) {
                add(new RootItem(local));
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Item item = getItem(position);
            return item.getView(convertView, parent);
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) != 1;
        }

        @Override
        public int getItemViewType(int position) {
            final Item item = getItem(position);
            if (item instanceof RootItem) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }
    }

    public static class RootComparator implements Comparator<RootInfo> {
        @Override
        public int compare(RootInfo lhs, RootInfo rhs) {
            final int score = DocumentInfo.compareToIgnoreCaseNullable(lhs.title, rhs.title);
            if (score != 0) {
                return score;
            } else {
                return DocumentInfo.compareToIgnoreCaseNullable(lhs.summary, rhs.summary);
            }
        }
    }
}
