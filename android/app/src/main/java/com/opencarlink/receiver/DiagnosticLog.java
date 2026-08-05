package com.opencarlink.receiver;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class DiagnosticLog {
    static final String FILE_NAME = "OpenCarLinkReceiver-log.txt";

    private final ContentResolver resolver;
    private Uri uri;

    DiagnosticLog(Context context) {
        resolver = context.getContentResolver();
    }

    synchronized void reset() {
        try {
            uri = findOrCreate();
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output != null) {
                    output.write("OpenCarLink Receiver diagnostic log\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException error) {
            uri = null;
        }
    }

    synchronized void append(String line) {
        try {
            if (uri == null) {
                uri = findOrCreate();
            }
            try (OutputStream output = resolver.openOutputStream(uri, "wa")) {
                if (output != null) {
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private Uri findOrCreate() throws IOException {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
            + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] arguments = {FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"};
        try (Cursor cursor = resolver.query(collection, projection, selection, arguments, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri created = resolver.insert(collection, values);
        if (created == null) {
            throw new IOException("无法创建公共诊断日志");
        }
        return created;
    }
}
