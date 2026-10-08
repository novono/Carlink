package com.opencarlink.receiver;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

/** Removes this app's legacy public log. New diagnostics live only in the active UI snapshot. */
final class DiagnosticLog {
    static final String FILE_NAME = "OpenCarLinkReceiver-log.txt";
    private final ContentResolver resolver;
    private final String packageName;

    DiagnosticLog(Context context) {
        resolver = context.getContentResolver();
        packageName = context.getPackageName();
        reset();
    }

    synchronized void reset() {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
            + MediaStore.Downloads.RELATIVE_PATH + "=? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?";
        String[] arguments = {FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/", packageName};
        try (Cursor cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID},
                selection, arguments, null)) {
            if (cursor == null) { return; }
            while (cursor.moveToNext()) {
                resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null);
            }
        } catch (RuntimeException ignored) {
            // Scoped storage may hide old files. Never request access to another app's history.
        }
    }
}
