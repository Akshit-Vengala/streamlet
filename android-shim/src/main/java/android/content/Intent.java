package android.content;

import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public static final String EXTRA_SUBJECT = "android.intent.extra.SUBJECT";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;

    private String action;
    private Uri data;
    private String type;
    private int flags;
    private ComponentName component;
    private final Map<String, Object> extras = new HashMap<>();

    public Intent() { }
    public Intent(String action) { this.action = action; }
    public Intent(String action, Uri data) { this.action = action; this.data = data; }

    public static Intent makeRestartActivityTask(ComponentName mainActivity) {
        Intent i = new Intent(ACTION_MAIN);
        i.component = mainActivity;
        return i;
    }

    public String getAction() { return action; }
    public Intent setAction(String a) { action = a; return this; }
    public Uri getData() { return data; }
    public Intent setData(Uri d) { data = d; return this; }
    public Intent setDataAndType(Uri d, String t) { data = d; type = t; return this; }
    public String getType() { return type; }
    public Intent setType(String t) { type = t; return this; }
    public int getFlags() { return flags; }
    public Intent setFlags(int f) { flags = f; return this; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public ComponentName getComponent() { return component; }
    public Intent setPackage(String p) { return this; }
    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, int v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, boolean v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, long v) { extras.put(k, v); return this; }
    public String getStringExtra(String k) { Object v = extras.get(k); return v instanceof String ? (String) v : null; }
    public int getIntExtra(String k, int d) { Object v = extras.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
    public boolean getBooleanExtra(String k, boolean d) { Object v = extras.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public boolean hasExtra(String k) { return extras.containsKey(k); }

    @Override public String toString() { return "Intent { act=" + action + " dat=" + data + " }"; }
}
