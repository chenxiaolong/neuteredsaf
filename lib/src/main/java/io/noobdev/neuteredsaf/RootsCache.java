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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.noobdev.neuteredsaf.DocumentsActivity.State;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat.Root;
import io.noobdev.neuteredsaf.internal.annotations.GuardedBy;
import io.noobdev.neuteredsaf.model.RootInfo;
import io.noobdev.neuteredsaf.providers.ExternalStorageProvider;
import io.noobdev.neuteredsaf.compat.ContentProviderClientCompat;
import io.noobdev.neuteredsaf.compat.IOUtils;
import io.noobdev.neuteredsaf.compat.ObjectsCompat;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {
    private static final boolean LOGD = false;

    public static final Uri sNotificationUri = Uri.parse(
            "content://io.noobdev.neuteredsaf.roots/");

    private final Context mContext;

    private final Object mLock = new Object();
    private final CountDownLatch mFirstLoad = new CountDownLatch(1);

    @GuardedBy("mLock")
    private Multimap<String, RootInfo> mRoots = ArrayListMultimap.create();

    public RootsCache(Context context) {
        mContext = context;
    }

    /**
     * Gather roots from all known storage providers.
     */
    public void updateAsync() {
        new UpdateTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void waitForFirstLoad() {
        boolean success = false;
        try {
            success = mFirstLoad.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        if (!success) {
            Log.w(DocumentsActivity.TAG, "Timeout waiting for first update");
        }
    }

    private class UpdateTask extends AsyncTask<Void, Void, Void> {
        private final Multimap<String, RootInfo> mTaskRoots = ArrayListMultimap.create();

        /**
         * Update all roots.
         */
        public UpdateTask() {
        }

        @Override
        protected Void doInBackground(Void... params) {
            final long start = SystemClock.elapsedRealtime();

            mTaskRoots.putAll(ExternalStorageProvider.AUTHORITY,
                    loadRootsForAuthority(mContext.getContentResolver(),
                            ExternalStorageProvider.AUTHORITY));

            final ContentResolver resolver = mContext.getContentResolver();

            final long delta = SystemClock.elapsedRealtime() - start;
            Log.d(DocumentsActivity.TAG, "Update found " + mTaskRoots.size() + " roots in " + delta + "ms");
            synchronized (mLock) {
                mRoots = mTaskRoots;
            }
            mFirstLoad.countDown();
            resolver.notifyChange(sNotificationUri, null, false);
            return null;
        }
    }

    /**
     * Bring up requested provider and query for all active roots.
     */
    private Collection<RootInfo> loadRootsForAuthority(ContentResolver resolver, String authority) {
        if (LOGD) Log.d(DocumentsActivity.TAG, "Loading roots for " + authority);

        final List<RootInfo> roots = Lists.newArrayList();
        final Uri rootsUri = DocumentsContractCompat.buildRootsUri(authority);

        ContentProviderClient client = null;
        Cursor cursor = null;
        try {
            client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, authority);
            cursor = client.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                final RootInfo root = RootInfo.fromRootsCursor(authority, cursor);
                roots.add(root);
            }
        } catch (Exception e) {
            Log.w(DocumentsActivity.TAG, "Failed to load some roots from " + authority + ": " + e);
        } finally {
            IOUtils.closeQuietly(cursor);
            ContentProviderClientCompat.releaseQuietly(client);
        }
        return roots;
    }

    public RootInfo getDefaultRoot() {
        return getRootOneshot(ExternalStorageProvider.AUTHORITY,
                ExternalStorageProvider.ROOT_ID_PRIMARY_EMULATED);
    }

    /**
     * Return the requested {@link RootInfo}, but only loading the roots for the
     * requested authority. This is useful when we want to load fast without
     * waiting for all the other roots to come back.
     */
    public RootInfo getRootOneshot(String authority, String rootId) {
        synchronized (mLock) {
            RootInfo root = getRootLocked(authority, rootId);
            if (root == null) {
                mRoots.putAll(
                        authority, loadRootsForAuthority(mContext.getContentResolver(), authority));
                root = getRootLocked(authority, rootId);
            }
            return root;
        }
    }

    public RootInfo getRootBlocking(String authority, String rootId) {
        waitForFirstLoad();
        synchronized (mLock) {
            return getRootLocked(authority, rootId);
        }
    }

    private RootInfo getRootLocked(String authority, String rootId) {
        for (RootInfo root : mRoots.get(authority)) {
            if (ObjectsCompat.equals(root.rootId, rootId)) {
                return root;
            }
        }
        return null;
    }

    public boolean isIconUniqueBlocking(RootInfo root) {
        waitForFirstLoad();
        synchronized (mLock) {
            final int rootIcon = root.derivedIcon != 0 ? root.derivedIcon : root.icon;
            for (RootInfo test : mRoots.get(root.authority)) {
                if (ObjectsCompat.equals(test.rootId, root.rootId)) {
                    continue;
                }
                final int testIcon = test.derivedIcon != 0 ? test.derivedIcon : test.icon;
                if (testIcon == rootIcon) {
                    return false;
                }
            }
            return true;
        }
    }

    public Collection<RootInfo> getRootsBlocking() {
        waitForFirstLoad();
        synchronized (mLock) {
            return mRoots.values();
        }
    }

    public Collection<RootInfo> getMatchingRootsBlocking(State state) {
        waitForFirstLoad();
        synchronized (mLock) {
            return getMatchingRoots(mRoots.values(), state);
        }
    }

    @VisibleForTesting
    static List<RootInfo> getMatchingRoots(Collection<RootInfo> roots, State state) {
        final List<RootInfo> matching = Lists.newArrayList();
        for (RootInfo root : roots) {
            final boolean supportsCreate = (root.flags & Root.FLAG_SUPPORTS_CREATE) != 0;
            final boolean empty = (root.flags & Root.FLAG_EMPTY) != 0;

            // Exclude read-only devices when creating
            if (state.action == State.ACTION_CREATE && !supportsCreate) continue;
            // Only show empty roots when creating
            if (state.action != State.ACTION_CREATE && empty) continue;

            // Only include roots that serve requested content
            final boolean overlap =
                    MimePredicate.mimeMatches(root.derivedMimeTypes, state.acceptMimes) ||
                    MimePredicate.mimeMatches(state.acceptMimes, root.derivedMimeTypes);
            if (!overlap) {
                continue;
            }

            matching.add(root);
        }
        return matching;
    }
}
