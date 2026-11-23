package com.iap2loc.android.engine;

import android.content.Context;
import android.net.Uri;

import com.iap2loc.android.protocol.MfiSigner;
import com.iap2loc.android.util.AppLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Holds the user-supplied MFi identity inside app-private storage. No key material is bundled. */
public final class IdentityStore {
    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    public static final String KEY_FILE = "identity.pk8";
    public static final String CERT_FILE = "certificate.p7b";

    private IdentityStore() {}

    public static File directory(Context context) {
        File folder = new File(context.getApplicationContext().getFilesDir(), "identity");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("无法创建 MFi 身份目录：" + folder.getAbsolutePath());
        }
        return folder;
    }

    public static boolean available(Context context) {
        return new File(directory(context), KEY_FILE).isFile() &&
                new File(directory(context), CERT_FILE).isFile();
    }

    public static void importFile(Context context, Uri uri, String name) throws IOException {
        if (!KEY_FILE.equals(name) && !CERT_FILE.equals(name)) {
            throw new IllegalArgumentException("不支持的 MFi 身份文件名：" + name);
        }
        File target = new File(directory(context), name);
        File pending = File.createTempFile("identity-", ".tmp", directory(context));
        try {
            copyImport(context, uri, pending);
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
        AppLog.ok("已导入身份文件 " + name + " (" + target.length() + " 字节)");
    }

    private static void copyImport(Context context, Uri uri, File target) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        }
        if (target.length() == 0) throw new IOException("所选 MFi 身份文件为空");
    }

    public static MfiSigner load(Context context) {
        File folder = directory(context);
        boolean hasKey = new File(folder, KEY_FILE).isFile();
        boolean hasCertificate = new File(folder, CERT_FILE).isFile();
        if (!hasKey && !hasCertificate) return null;
        if (!hasKey || !hasCertificate) throw new IllegalStateException(
                "MFi 身份材料不完整：需要同时导入 identity.pk8 与 certificate.p7b");
        try {
            byte[] key = Files.readAllBytes(new File(folder, KEY_FILE).toPath());
            byte[] certificate = Files.readAllBytes(new File(folder, CERT_FILE).toPath());
            IdentityInspection.inspect(key, certificate);
            MfiSigner signer = MfiSigner.fromMaterial(key, certificate);
            AppLog.info("MFi 身份已加载，证书 " + certificate.length + " 字节");
            return signer;
        } catch (Exception ex) {
            throw new IllegalStateException("MFi 身份加载失败：" + ex.getMessage(), ex);
        }
    }

    public static String inspect(Context context) {
        File folder = directory(context);
        try {
            byte[] key = Files.readAllBytes(new File(folder, KEY_FILE).toPath());
            byte[] certificate = Files.readAllBytes(new File(folder, CERT_FILE).toPath());
            return "来源：" + folder.getAbsolutePath() + "\n" + IdentityInspection.inspect(key, certificate);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 MFi 身份失败：" + ex.getMessage(), ex);
        }
    }

    public static String describe(Context context) {
        return directory(context).getAbsolutePath();
    }

    public static void clear(Context context) {
        File folder = directory(context);
        try {
            Files.deleteIfExists(new File(folder, KEY_FILE).toPath());
            Files.deleteIfExists(new File(folder, CERT_FILE).toPath());
        } catch (IOException ex) {
            throw new IllegalStateException("删除 MFi 身份失败：" + ex.getMessage(), ex);
        }
    }
}
