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

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.os.RemoteException;
import android.text.format.DateUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public class DocumentsApplication {
    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    private static String sApplicationId;

    private static WeakReference<Context> sContext;
    private static RootsCache sRoots;
    private static Point sThumbnailsSize;
    private static ThumbnailCache sThumbnails;

    private DocumentsApplication() {
    }

    public static RootsCache getRootsCache(Context context) {
        return sRoots;
    }

    public static ThumbnailCache getThumbnailsCache(Context context, Point size) {
        final ThumbnailCache thumbnails = sThumbnails;
        if (!size.equals(sThumbnailsSize)) {
            thumbnails.evictAll();
            sThumbnailsSize = size;
        }
        return thumbnails;
    }

    public static ContentProviderClient acquireUnstableProviderOrThrow(
            ContentResolver resolver, String authority) throws RemoteException {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                authority);
        if (client == null) {
            throw new RemoteException("Failed to acquire provider for " + authority);
        }

        try {
            Method setDetectNotResponding = ContentProviderClient.class.getMethod(
                    "setDetectNotResponding", Long.class);
            setDetectNotResponding.invoke(client, PROVIDER_ANR_TIMEOUT);
        } catch (Exception e) {
            // Ignore
        }

        return client;
    }

    public static String getApplicationId() {
        return sApplicationId;
    }

    public static void setApplicationId(Context context) {
        if (sApplicationId == null) {
            sApplicationId = context.getPackageName();
        }
    }

    public static void install(Context context) {
        if (sContext != null) {
            throw new IllegalStateException("Tried to call install() twice!");
        }

        Context applicationContext = context.getApplicationContext();
        sContext = new WeakReference<>(applicationContext);

        setApplicationId(context);

        final ActivityManager am = (ActivityManager)
                applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
        final int memoryClassBytes = am.getMemoryClass() * 1024 * 1024;

        sRoots = new RootsCache(applicationContext);
        sRoots.updateAsync();

        sThumbnails = new ThumbnailCache(memoryClassBytes / 4);

        final IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        applicationContext.registerReceiver(sCacheReceiver, localeFilter);
    }

    public static void onTrimMemory(int level) {
        if (level >= Application.TRIM_MEMORY_MODERATE) {
            sThumbnails.evictAll();
        } else if (level >= Application.TRIM_MEMORY_BACKGROUND) {
            sThumbnails.trimToSize(sThumbnails.size() / 2);
        }
    }

    private static BroadcastReceiver sCacheReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            sRoots.updateAsync();
        }
    };
}
