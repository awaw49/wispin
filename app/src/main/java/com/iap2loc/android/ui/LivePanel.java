package com.iap2loc.android.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;

import com.iap2loc.android.R;

import org.json.JSONArray;
import org.json.JSONObject;

/** Drives the status pill and the start/stop button from aggregated session state. */
public final class LivePanel {
    private final ControlContext ui;
    private final Fields fields;
    private boolean anyRunning;

    public LivePanel(ControlContext ui) {
        this.ui = ui;
        fields = new Fields(ui.activity);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ui.activity.getColor(R.color.muted));
        ui.activity.findViewById(R.id.statusDot).setBackground(dot);
    }

    public void refresh(JSONArray sessions) {
        anyRunning = updateStatus(sessions);
    }

    public boolean anyRunning() { return anyRunning; }

    /** Aggregates only running sessions so finished ones fall through to 已断开 instead of 未连接. */
    private boolean updateStatus(JSONArray sessions) {
        Status status = new Status();
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject item = sessions.optJSONObject(index);
            if (item == null) continue;
            status.count++;
            int itemSent = item.optInt("sent");
            status.totalSent += itemSent;
            if (!item.isNull("error")) status.error = item.optString("error");
            if (!item.optBoolean("running")) continue;
            status.running = true;
            status.authenticated |= item.optBoolean("authenticated");
            status.subscribed |= item.optBoolean("subscribed");
            status.sent += itemSent;
        }
        renderStatus(status);
        return status.running;
    }

    private void renderStatus(Status status) {
        int color = R.color.muted;
        String text = "未连接";
        String cta = "开始定位";
        int ctaBackground = R.drawable.bg_primary;
        if (status.subscribed) {
            color = R.color.ok;
            text = "定位推送中 · " + status.sent + " 帧";
            cta = "停止定位";
            ctaBackground = R.drawable.bg_danger;
        } else if (status.authenticated) {
            color = R.color.accent;
            text = "已认证 · 等待 iPhone 订阅";
            cta = "停止定位";
            ctaBackground = R.drawable.bg_danger;
        } else if (status.running) {
            color = R.color.warn;
            text = "连接中…";
            cta = "停止";
            ctaBackground = R.drawable.bg_danger;
        } else if (status.error != null) {
            color = R.color.err;
            text = "连接失败：" + status.error;
        } else if (status.count > 0) {
            text = status.totalSent > 0 ? "已断开 · 已推 " + status.totalSent + " 帧" : "已断开";
        }
        fields.set(R.id.statusText, text);
        GradientDrawable dot = (GradientDrawable) ui.activity.findViewById(R.id.statusDot).getBackground();
        dot.setColor(ui.activity.getColor(color));
        Button ctaButton = ui.activity.findViewById(R.id.ctaButton);
        ctaButton.setText(cta);
        ctaButton.setBackgroundResource(ctaBackground);
        ui.activity.findViewById(R.id.retryPermissions)
                .setVisibility(ui.bound() ? View.GONE : View.VISIBLE);
    }

    /** Session aggregation for the status pill: only running sessions drive live phases. */
    private static final class Status {
        int count;
        boolean running;
        boolean authenticated;
        boolean subscribed;
        int sent;
        int totalSent;
        String error;
    }
}
