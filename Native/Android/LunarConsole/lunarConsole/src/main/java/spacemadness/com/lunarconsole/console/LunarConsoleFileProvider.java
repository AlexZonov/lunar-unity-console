//
//  LunarConsoleFileProvider.java
//
//  Lunar Unity Mobile Console
//  https://github.com/SpaceMadness/lunar-unity-console
//
//  Copyright 2015-2021 Alex Lementuev, SpaceMadness.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package spacemadness.com.lunarconsole.console;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves cached console log files to other apps. A self-contained replacement for
 * androidx.core.content.FileProvider: the plugin ships into projects which don't
 * necessarily have androidx on the classpath, and a missing provider class kills
 * the process on startup.
 */
public class LunarConsoleFileProvider extends ContentProvider {
    private static final String AUTHORITY_SUFFIX = ".lunarconsole.fileprovider";
    private static final String MIME_TYPE = "text/plain";

    public static Uri getUriForFile(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Only read-only access is supported: " + mode);
        }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file = resolveFileOrNull(uri);
        if (file == null) {
            return null;
        }

        final String[] columns = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

        final Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; ++i) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.length();
            }
        }

        final MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        final File file = resolveFileOrNull(uri);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("No such log file: " + uri);
        }
        return file;
    }

    /**
     * Maps an uri to a file inside the log cache directory. Returns null for anything
     * pointing outside of it (path traversal, nested paths, etc).
     */
    private File resolveFileOrNull(Uri uri) {
        final Context context = getContext();
        if (context == null) {
            return null;
        }

        final String name = uri.getLastPathSegment();
        if (name == null || uri.getPathSegments().size() != 1) {
            return null;
        }

        try {
            final File logsDir = ConsoleLogView.getLogsDir(context).getCanonicalFile();
            final File file = new File(logsDir, name).getCanonicalFile();
            return logsDir.equals(file.getParentFile()) ? file : null;
        } catch (IOException e) {
            return null;
        }
    }
}
