package com.iap2loc.android.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-process protocol log. Kept independent of android.util.Log so JVM unit tests can use it. */
public final class AppLog {
    public static final String INFO = "INFO";
    public static final String STEP = "STEP";
    public static final String OK = "OK";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    private static final int MAX = 1200;
    private static final Object GATE = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile Sink sink;

    private AppLog() {}

    public interface Sink {
        void onEntry(Entry entry);
    }

    public static final class Entry {
        public final String time;
        public final String level;
        public final String text;

        Entry(String time, String level, String text) {
            this.time = time;
            this.level = level;
            this.text = text;
        }

        public String format() {
            return time + " " + level + " " + text;
        }
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void info(String text) { add(INFO, text); }
    public static void step(String text) { add(STEP, text); }
    public static void ok(String text) { add(OK, text); }
    public static void warn(String text) { add(WARN, text); }
    public static void error(String text) { add(ERROR, text); }

    private static void add(String level, String text) {
        String safe = text == null ? "" : text;
        Entry entry;
        synchronized (GATE) {
            entry = new Entry(TIME.format(new Date()), level, safe);
            ENTRIES.add(entry);
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        }
        Sink current = sink;
        if (current != null) current.onEntry(entry);
    }

    public static String text(int limit) {
        StringBuilder builder = new StringBuilder();
        synchronized (GATE) {
            int start = Math.max(0, ENTRIES.size() - limit);
            for (int i = start; i < ENTRIES.size(); i++) {
                builder.append(ENTRIES.get(i).format()).append('\n');
            }
        }
        return builder.toString();
    }

    public static void clear() {
        synchronized (GATE) {
            ENTRIES.clear();
        }
    }
}
