package com.iap2loc.android.ui;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.iap2loc.android.R;
import com.iap2loc.android.engine.AppConfig;
import com.iap2loc.android.protocol.AccessoryProfile;

import org.json.JSONObject;

/** Accessory profile editing and read-only identity material inspection. */
public final class IdentityPanel {
    private final ControlContext ui;
    private final Fields fields;

    public IdentityPanel(ControlContext ui) {
        this.ui = ui;
        fields = new Fields(ui.activity);
        ui.activity.findViewById(R.id.editProfile).setOnClickListener(v -> ui.run(this::edit));
        ui.activity.findViewById(R.id.inspectIdentity).setOnClickListener(v -> ui.run(this::inspect));
        ui.activity.findViewById(R.id.rotateIdentity).setOnClickListener(v -> ui.run(() -> {
            ui.service().rotateIdentity();
            ui.message("身份已轮换，将在新会话中生效");
        }));
        for (int id : new int[]{R.id.randomIdentityCheck, R.id.randomAdapterCheck}) {
            ui.activity.findViewById(id).setOnClickListener(v -> ui.run(this::saveFlags));
        }
    }

    public void load() {
        AppConfig config = AppConfig.load(ui.activity);
        fields.check(R.id.randomIdentityCheck,
                config.randomizeIdentity && config.rotateIdentityOnStart);
        fields.check(R.id.randomAdapterCheck,
                config.randomizeAdapter && config.useAdapterOverride);
    }

    private void saveFlags() {
        ui.service().setConfig(fields.checked(R.id.randomIdentityCheck),
                fields.checked(R.id.randomAdapterCheck));
    }

    public void refresh(JSONObject identity) {
        String status = identity.optBoolean("material") ? "已导入，连接时验证" : "未导入完整材料";
        fields.set(R.id.identityInfo, "名称 " + identity.optString("name") +
                "\n型号 " + identity.optString("model") + "\n序号 " + identity.optString("serial") +
                "\n协议上报地址 " + identity.optString("adapter", "本机") + "\nMFi：" + status);
    }

    private void edit() {
        AccessoryProfile profile = AppConfig.load(ui.activity).accessory;
        String[] labels = {"配件名称", "型号", "制造商", "序列号", "固件版本", "硬件版本", "上报蓝牙地址（留空使用本机）"};
        String[] values = {profile.name, profile.model, profile.manufacturer, profile.serial,
                profile.firmware, profile.hardware, profile.adapterAddress};
        LinearLayout form = new LinearLayout(ui.activity);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText[] edits = createFields(form, labels, values);
        ScrollView scroll = new ScrollView(ui.activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(ui.activity).setTitle("编辑配件身份")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> ui.run(() -> saveProfile(edits, dialog))));
        dialog.show();
    }

    private EditText[] createFields(LinearLayout form, String[] labels, String[] values) {
        EditText[] edits = new EditText[labels.length];
        for (int index = 0; index < labels.length; index++) {
            TextView label = new TextView(ui.activity);
            label.setText(labels[index]);
            form.addView(label);
            EditText edit = new EditText(ui.activity);
            edit.setInputType(InputType.TYPE_CLASS_TEXT);
            edit.setSingleLine(true);
            edit.setText(values[index]);
            form.addView(edit);
            edits[index] = edit;
        }
        return edits;
    }

    private void saveProfile(EditText[] edits, AlertDialog dialog) {
        AccessoryProfile profile = new AccessoryProfile();
        profile.name = value(edits[0]);
        profile.model = value(edits[1]);
        profile.manufacturer = value(edits[2]);
        profile.serial = value(edits[3]);
        profile.firmware = value(edits[4]);
        profile.hardware = value(edits[5]);
        profile.adapterAddress = value(edits[6]);
        ui.service().setProfile(profile);
        dialog.dismiss();
        ui.message("配件身份已保存，新会话使用更新值");
    }

    private static String value(EditText edit) {
        return edit.getText().toString().trim();
    }

    private void inspect() throws Exception {
        String report = ui.service().inspectIdentity();
        TextView content = new TextView(ui.activity);
        content.setText(report);
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(ui.activity);
        scroll.addView(content);
        new AlertDialog.Builder(ui.activity).setTitle("MFi 身份检查")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }
}
