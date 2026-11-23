package com.iap2loc.android.ui;

import android.app.Activity;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.iap2loc.android.R;
import com.iap2loc.android.map.MapRouteJson;
import com.iap2loc.android.map.RouteEditor;
import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;
import com.iap2loc.android.util.AppLog;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Embedded Leaflet surface: live draft edits are reported through the listener, no confirm step. */
public final class MapSurface {
    public interface Listener {
        void onDraft(List<RoutePath.Vertex> points);
        void onError(String message);
    }

    private static final String MAP_URL = "file:///android_asset/map.html";
    private static final int MAP_ZOOM = 14;
    private final Activity activity;
    private final Listener listener;
    private WebView webView;
    private String datum = "gcj";
    private boolean pathMode;
    private boolean loopClosed;
    private boolean ready;
    private RouteEditor editor = new RouteEditor(Collections.emptyList());
    private Geo.Point center = new Geo.Point(22.3825, 114.1880);

    public MapSurface(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void attach() {
        webView = activity.findViewById(R.id.mapView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new Bridge(), "AndroidMap");
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    listener.onError("地图脚本：" + message.message());
                }
                return true;
            }
        });
        webView.loadUrl(MAP_URL);
    }

    public void setPathMode(boolean on) {
        pathMode = on;
        editor = on ? editor : new RouteEditor(editor.points().isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(editor.points().get(editor.points().size() - 1)));
        if (ready) evaluate("setPathMode(" + on + ")");
        render();
    }

    public void setLoopClosed(boolean on) {
        if (loopClosed == on) return;
        loopClosed = on;
        if (ready) evaluate("setLoopClosed(" + on + ")");
        render();
    }

    public void setDatum(String next) {
        datum = next;
        render();
    }

    public String datum() { return datum; }

    public void setPoints(List<RoutePath.Vertex> points) {
        editor = new RouteEditor(points);
        render();
    }

    public RouteEditor editor() { return editor; }

    public void undo() { update(editor.undo()); }

    public void clear() { update(editor.clear()); }

    public void centerOn(Geo.Point point) {
        center = point;
        double[] display = display(point);
        if (ready) evaluate(String.format(Locale.US, "center(%.7f,%.7f)", display[0], display[1]));
    }

    private void update(RouteEditor next) {
        editor = next;
        render();
        notifyDraft();
    }

    private void notifyDraft() {
        listener.onDraft(Collections.unmodifiableList(editor.points()));
    }

    private void render() {
        if (!ready) return;
        try {
            evaluate("renderRoute(" + MapRouteJson.encode(editor, datum) + ",'" + datum + "')");
        } catch (Exception ex) {
            listener.onError("地图渲染失败：" + ex.getMessage());
        }
    }

    private double[] display(Geo.Point point) {
        return "gcj".equals(datum) ? Geo.wgs84ToGcj02(point.latitude, point.longitude)
                : new double[]{point.latitude, point.longitude};
    }

    private Geo.Point fromDisplay(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            throw new IllegalArgumentException("地图返回了非有限坐标");
        }
        double[] point = "gcj".equals(datum) ? Geo.gcj02ToWgs84(lat, lon) : new double[]{lat, lon};
        return new Geo.Point(point[0], Geo.normalizeLongitude(point[1]));
    }

    private void evaluate(String script) {
        webView.evaluateJavascript("try { " + script + "; } catch(error) { AndroidMap.onError(String(error)); }", null);
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            listener.onError("已阻止地图页面跳转：" + request.getUrl());
            return true;
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!MAP_URL.equals(url)) {
                listener.onError("地图页面来源无效");
                return;
            }
            double[] display = display(center);
            evaluate(String.format(Locale.US, "init(%.7f,%.7f,%d)", display[0], display[1], MAP_ZOOM));
            evaluate("setPathMode(" + pathMode + ")");
            evaluate("setLoopClosed(" + loopClosed + ")");
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) listener.onError("地图加载失败：" + error.getDescription());
            else AppLog.warn("地图资源加载失败：" + request.getUrl());
        }
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() {
            activity.runOnUiThread(() -> {
                ready = true;
                render();
            });
        }

        @JavascriptInterface public void onPick(double lat, double lon) {
            activity.runOnUiThread(() -> {
                try {
                    Geo.Point point = fromDisplay(lat, lon);
                    update(pathMode ? editor.add(point)
                            : new RouteEditor(Collections.singletonList(new RoutePath.Vertex(point, null))));
                } catch (Exception ex) {
                    listener.onError(ex.getMessage());
                }
            });
        }

        @JavascriptInterface public void onMove(int index, double lat, double lon) {
            activity.runOnUiThread(() -> {
                try {
                    update(editor.move(index, fromDisplay(lat, lon)));
                } catch (Exception ex) {
                    listener.onError(ex.getMessage());
                }
            });
        }

        @JavascriptInterface public void onDelete(int index) {
            activity.runOnUiThread(() -> {
                try {
                    update(editor.remove(index));
                } catch (Exception ex) {
                    listener.onError(ex.getMessage());
                }
            });
        }

        @JavascriptInterface public void onError(String message) {
            activity.runOnUiThread(() -> listener.onError("地图脚本：" + message));
        }

        @JavascriptInterface public void onTileError(String message) {
            AppLog.warn("地图瓦片不可用：" + message);
        }
    }

    public void destroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidMap");
            webView.destroy();
        }
    }
}
