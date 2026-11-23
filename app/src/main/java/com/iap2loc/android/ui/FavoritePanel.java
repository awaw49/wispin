package com.iap2loc.android.ui;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.iap2loc.android.R;

import org.json.JSONArray;
import org.json.JSONObject;

/** Favorites render as map-side chips: tap applies, long-press deletes, star saves the current point. */
public final class FavoritePanel {
    private static final int CHIP_PADDING_DP = 10;
    private static final int CHIP_MARGIN_DP = 6;
    private final ControlContext ui;
    private final Fields fields;
    private final TargetPanel target;
    private String previous;
    private OnFavoriteApplied appliedListener;

    public interface OnFavoriteApplied {
        void onFavorite(JSONObject favorite);
    }

    public FavoritePanel(ControlContext ui, TargetPanel target) {
        this.ui = ui;
        this.target = target;
        fields = new Fields(ui.activity);
        ui.activity.findViewById(R.id.favSave).setOnClickListener(v -> ui.run(this::promptName));
        ui.activity.findViewById(R.id.favToggle).setOnClickListener(v -> toggleBar());
    }

    public void setAppliedListener(OnFavoriteApplied listener) { appliedListener = listener; }

    private void toggleBar() {
        View bar = ui.activity.findViewById(R.id.favoritesBar);
        bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void promptName() throws Exception {
        double lat = target.latitude();
        double lon = target.longitude();
        EditText input = new EditText(ui.activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("收藏点名称");
        input.setText("收藏点");
        input.selectAll();
        new AlertDialog.Builder(ui.activity).setTitle("保存当前位置")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> ui.run(() -> save(input, lat, lon)))
                .show();
    }

    private void save(EditText input, double lat, double lon) throws Exception {
        String name = input.getText().toString().trim();
        ui.service().addFavorite(name.isEmpty() ? "收藏点" : name, lat, lon, null, null);
        ui.message("收藏点已保存");
    }

    public void refresh() throws Exception {
        String json = ui.service().favoritesJson();
        if (json.equals(previous)) return;
        JSONArray favorites = new JSONArray(json);
        LinearLayout container = ui.activity.findViewById(R.id.favoritesContainer);
        container.removeAllViews();
        for (int index = 0; index < favorites.length(); index++) {
            container.addView(chip(favorites.getJSONObject(index)));
        }
        View bar = ui.activity.findViewById(R.id.favoritesBar);
        if (favorites.length() == 0) bar.setVisibility(View.GONE);
        previous = json;
    }

    private TextView chip(JSONObject favorite) throws Exception {
        float density = ui.activity.getResources().getDisplayMetrics().density;
        TextView view = new TextView(ui.activity);
        view.setText(favorite.getString("name"));
        view.setTextColor(ui.activity.getColor(R.color.ink));
        view.setTextSize(13);
        view.setBackgroundResource(R.drawable.bg_chip);
        int padding = (int) (CHIP_PADDING_DP * density);
        view.setPadding(padding, padding / 2, padding, padding / 2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd((int) (CHIP_MARGIN_DP * density));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> ui.run(() -> apply(favorite)));
        view.setOnLongClickListener(v -> {
            ui.run(() -> ui.service().removeFavorite(favorite.getString("id")));
            return true;
        });
        return view;
    }

    private void apply(JSONObject favorite) throws Exception {
        target.applyFavorite(favorite);
        if (appliedListener != null) appliedListener.onFavorite(favorite);
        ui.message("已切换到 " + favorite.getString("name"));
    }
}
