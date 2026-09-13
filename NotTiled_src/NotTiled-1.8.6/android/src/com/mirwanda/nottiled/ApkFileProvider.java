package com.mirwanda.nottiled;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 自动更新用的最小 ContentProvider：把 App 私有目录 {@code filesDir/update/} 下的 APK
 * 以 {@code content://} 形式暴露给系统安装器（Android 7.0+ 不允许再用 file:// 传递文件）。
 *
 * <p>URI 形式：{@code content://<applicationId>.update/<fileName>}
 * （authority 由 applicationId 派生，与 AndroidManifest.xml 中注册的 {@code ${applicationId}.update} 一致，
 * 保证不同 applicationId 的应用（如 com.momo.tunit 与 com.mirwanda.nottiled）可共存安装）。</p>
 *
 * <p>本类刻意不依赖 androidx，避免改动工程整体的 AndroidX 配置。</p>
 */
public class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".update";
    /** 下载目录名：私有 filesDir/update/，必须与 MainActivity 下载路径保持一致。 */
    public static final String DIR = "update";

    /** 依据文件名构造 content:// URI。 */
    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + fileName);
    }

    private File resolve(String fileName) {
        // 仅允许纯文件名，防目录穿越
        if (fileName == null || fileName.isEmpty()) return null;
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return null;
        File dir = new File(getContext().getFilesDir(), DIR);
        return new File(dir, fileName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(lastSegment(uri));
        if (f == null || !f.exists()) return null;
        // 安装器通常查询 DISPLAY_NAME 与 SIZE
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(lastSegment(uri));
        if (f == null || !f.exists()) throw new FileNotFoundException("更新文件不存在: " + uri);
        // 只读打开（安装器只需读取）
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    private static String lastSegment(Uri uri) {
        return (uri == null) ? null : uri.getLastPathSegment();
    }
}
