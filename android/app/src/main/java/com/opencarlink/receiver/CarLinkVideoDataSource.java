package com.opencarlink.receiver;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

@UnstableApi
final class CarLinkVideoDataSource extends BaseDataSource {
    private VideoStreamHub.Reader reader;
    private Uri uri;
    private boolean opened;

    CarLinkVideoDataSource() {
        super(true);
    }

    @Override
    public long open(DataSpec dataSpec) {
        uri = dataSpec.uri;
        reader = VideoStreamHub.openReader();
        transferInitializing(dataSpec);
        opened = true;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        VideoStreamHub.Reader value = reader;
        if (value == null) {
            return C.RESULT_END_OF_INPUT;
        }
        try {
            int count = value.read(buffer, offset, length);
            if (count > 0) {
                bytesTransferred(count);
            }
            return count;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("视频流读取被中断", error);
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        VideoStreamHub.Reader value = reader;
        reader = null;
        if (value != null) {
            value.close();
        }
        uri = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
