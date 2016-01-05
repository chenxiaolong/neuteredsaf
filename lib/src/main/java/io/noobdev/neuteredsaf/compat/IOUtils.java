package io.noobdev.neuteredsaf.compat;

import java.io.Closeable;
import java.io.IOException;

public final class IOUtils {
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
