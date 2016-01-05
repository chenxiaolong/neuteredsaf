package io.noobdev.neuteredsaf.compat;

import android.content.ContentProviderClient;

public final class ContentProviderClientCompat {
    public static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                client.release();
            } catch (Exception ignored) {
            }
        }
    }
}
