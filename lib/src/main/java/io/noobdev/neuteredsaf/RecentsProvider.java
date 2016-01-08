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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import io.noobdev.neuteredsaf.compat.DocumentsContractCompat.Document;
import io.noobdev.neuteredsaf.compat.DocumentsContractCompat.Root;

public class RecentsProvider extends ContentProvider {
    private static final String TAG = "RecentsProvider";

    private static final String AUTHORITY_SUFFIX = ".neuteredsaf.recents";

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static boolean sMatcherInitialized = false;

    private static final int URI_STATE = 2;
    private static final int URI_RESUME = 3;

    public static final String TABLE_STATE = "state";
    public static final String TABLE_RESUME = "resume";

    public static class StateColumns {
        public static final String AUTHORITY = "authority";
        public static final String ROOT_ID = Root.COLUMN_ROOT_ID;
        public static final String DOCUMENT_ID = Document.COLUMN_DOCUMENT_ID;
        public static final String MODE = "mode";
        public static final String SORT_ORDER = "sortOrder";
    }

    public static class ResumeColumns {
        public static final String PACKAGE_NAME = "package_name";
        public static final String STACK = "stack";
    }

    public static Uri buildState(String authority, String rootId, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(getAuthority())
                .appendPath("state").appendPath(authority).appendPath(rootId).appendPath(documentId)
                .build();
    }

    public static Uri buildResume(String packageName) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(getAuthority()).appendPath("resume").appendPath(packageName).build();
    }

    public static String getAuthority() {
        return DocumentsApplication.getApplicationId() + AUTHORITY_SUFFIX;
    }

    private static UriMatcher getMatcher() {
        if (!sMatcherInitialized) {
            // state/authority/rootId/docId
            sMatcher.addURI(getAuthority(), "state/*/*/*", URI_STATE);
            // resume/packageName
            sMatcher.addURI(getAuthority(), "resume/*", URI_RESUME);
            sMatcherInitialized = true;
        }
        return sMatcher;
    }

    private DatabaseHelper mHelper;

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "recents.db";

        private static final int VERSION_ADD_RECENT_KEY = 5;

        public DatabaseHelper(Context context) {
            super(context, DB_NAME, null, VERSION_ADD_RECENT_KEY);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            db.execSQL("CREATE TABLE " + TABLE_STATE + " (" +
                    StateColumns.AUTHORITY + " TEXT," +
                    StateColumns.ROOT_ID + " TEXT," +
                    StateColumns.DOCUMENT_ID + " TEXT," +
                    StateColumns.MODE + " INTEGER," +
                    StateColumns.SORT_ORDER + " INTEGER," +
                    "PRIMARY KEY (" + StateColumns.AUTHORITY + ", " + StateColumns.ROOT_ID + ", "
                    + StateColumns.DOCUMENT_ID + ")" +
                    ")");

            db.execSQL("CREATE TABLE " + TABLE_RESUME + " (" +
                    ResumeColumns.PACKAGE_NAME + " TEXT NOT NULL PRIMARY KEY," +
                    ResumeColumns.STACK + " BLOB DEFAULT NULL" +
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database; wiping app data");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_STATE);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RESUME);
            onCreate(db);
        }
    }

    @Override
    public boolean onCreate() {
        DocumentsApplication.setApplicationId(getContext());
        mHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final SQLiteDatabase db = mHelper.getReadableDatabase();
        switch (getMatcher().match(uri)) {
            case URI_STATE:
                final String authority = uri.getPathSegments().get(1);
                final String rootId = uri.getPathSegments().get(2);
                final String documentId = uri.getPathSegments().get(3);
                return db.query(TABLE_STATE, projection, StateColumns.AUTHORITY + "=? AND "
                        + StateColumns.ROOT_ID + "=? AND " + StateColumns.DOCUMENT_ID + "=?",
                        new String[] { authority, rootId, documentId }, null, null, sortOrder);
            case URI_RESUME:
                final String packageName = uri.getPathSegments().get(1);
                return db.query(TABLE_RESUME, projection, ResumeColumns.PACKAGE_NAME + "=?",
                        new String[] { packageName }, null, null, sortOrder);
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
        }
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        final SQLiteDatabase db = mHelper.getWritableDatabase();
        final ContentValues key = new ContentValues();
        switch (getMatcher().match(uri)) {
            case URI_STATE:
                final String authority = uri.getPathSegments().get(1);
                final String rootId = uri.getPathSegments().get(2);
                final String documentId = uri.getPathSegments().get(3);

                key.put(StateColumns.AUTHORITY, authority);
                key.put(StateColumns.ROOT_ID, rootId);
                key.put(StateColumns.DOCUMENT_ID, documentId);

                // Ensure that row exists, then update with changed values
                db.insertWithOnConflict(TABLE_STATE, null, key, SQLiteDatabase.CONFLICT_IGNORE);
                db.update(TABLE_STATE, values, StateColumns.AUTHORITY + "=? AND "
                        + StateColumns.ROOT_ID + "=? AND " + StateColumns.DOCUMENT_ID + "=?",
                        new String[] { authority, rootId, documentId });

                return uri;
            case URI_RESUME:
                final String packageName = uri.getPathSegments().get(1);
                key.put(ResumeColumns.PACKAGE_NAME, packageName);

                // Ensure that row exists, then update with changed values
                db.insertWithOnConflict(TABLE_RESUME, null, key, SQLiteDatabase.CONFLICT_IGNORE);
                db.update(TABLE_RESUME, values, ResumeColumns.PACKAGE_NAME + "=?",
                        new String[] { packageName });
                return uri;
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
        }
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }
}
