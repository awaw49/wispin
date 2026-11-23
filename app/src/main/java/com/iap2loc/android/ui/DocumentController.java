package com.iap2loc.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.iap2loc.android.R;
import com.iap2loc.android.engine.Gpx;
import com.iap2loc.android.engine.IdentityStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Handles URI-bearing document results separately from map results with extras only. */
public final class DocumentController {
    private static final int GPX = 3;
    private static final int KEY = 4;
    private static final int CERTIFICATE = 5;
    private static final int COPY_BUFFER_BYTES = 8192;
    private final ControlContext ui;
    private final TargetPanel target;

    public DocumentController(ControlContext ui, TargetPanel target) {
        this.ui = ui;
        this.target = target;
        ui.activity.findViewById(R.id.gpxButton).setOnClickListener(v -> pick(GPX));
        ui.activity.findViewById(R.id.importKey).setOnClickListener(v -> pick(KEY));
        ui.activity.findViewById(R.id.importCert).setOnClickListener(v -> pick(CERTIFICATE));
    }

    private void pick(int requestCode) {
        ui.run(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            ui.activity.startActivityForResult(intent, requestCode);
        });
    }

    public void receive(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        ui.run(() -> receiveResult(requestCode, data));
    }

    private void receiveResult(int requestCode, Intent data) throws Exception {
        Uri uri = data.getData();
        if (uri == null) throw new IOException("文档选择器未返回文件 URI");
        if (requestCode == GPX) {
            target.receiveGpx(Gpx.parse(read(uri)));
            return;
        }
        if (requestCode == KEY || requestCode == CERTIFICATE) {
            String name = requestCode == KEY ? IdentityStore.KEY_FILE : IdentityStore.CERT_FILE;
            IdentityStore.importFile(ui.activity, uri, name);
            ui.message("已导入 " + name + "，可点击“检查 MFi 身份”查看验证结果");
        }
    }

    private String read(Uri uri) throws IOException {
        try (InputStream input = ui.activity.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
