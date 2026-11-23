package com.iap2loc.android.ui;

import android.app.Activity;
import android.widget.Toast;

import com.iap2loc.android.Iap2Service;

import java.util.function.Supplier;

/** The Activity owns binding; panels receive the current service through this boundary. */
public final class ControlContext {
    public final Activity activity;
    private final Supplier<Iap2Service> service;

    public ControlContext(Activity activity, Supplier<Iap2Service> service) {
        this.activity = activity;
        this.service = service;
    }

    public Iap2Service service() {
        Iap2Service current = service.get();
        if (current == null) throw new IllegalStateException("服务未就绪，请允许蓝牙权限后重试");
        return current;
    }

    public boolean bound() {
        return service.get() != null;
    }

    public void run(Action action) {
        try {
            action.run();
        } catch (Exception ex) {
            message("操作失败：" + ex.getMessage());
        }
    }

    public void message(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    public interface Action {
        void run() throws Exception;
    }
}
