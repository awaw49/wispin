package com.iap2loc.android.ui;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

/** Strict numeric parsing at the UI boundary. Empty optional fields are kept explicit. */
public final class Fields {
    private final Activity activity;

    public Fields(Activity activity) {
        this.activity = activity;
    }

    public String text(int id) {
        return ((EditText) activity.findViewById(id)).getText().toString().trim();
    }

    public void set(int id, Object value) {
        TextView view = activity.findViewById(id);
        String next = value == null ? "" : String.valueOf(value);
        if (!next.contentEquals(view.getText())) view.setText(next);
    }

    public boolean checked(int id) {
        return ((android.widget.CompoundButton) activity.findViewById(id)).isChecked();
    }

    public void check(int id, boolean value) {
        ((android.widget.CompoundButton) activity.findViewById(id)).setChecked(value);
    }

    public double number(int id) {
        String raw = text(id);
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw invalid(id, "请输入有限数字");
        }
    }

    public Double optional(int id) {
        return text(id).isEmpty() ? null : number(id);
    }

    public int integer(int id) {
        try {
            return Integer.parseInt(text(id));
        } catch (NumberFormatException ex) {
            throw invalid(id, "请输入整数");
        }
    }

    private IllegalArgumentException invalid(int id, String reason) {
        EditText edit = activity.findViewById(id);
        edit.setError(reason);
        edit.requestFocus();
        return new IllegalArgumentException(edit.getHint() + "：" + reason);
    }
}
