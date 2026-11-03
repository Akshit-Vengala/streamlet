package android.os;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Bundle {
    private final Map<String, Object> map = new HashMap<>();

    public Bundle() { }

    public void putString(String k, String v) { map.put(k, v); }
    public void putInt(String k, int v) { map.put(k, v); }
    public void putLong(String k, long v) { map.put(k, v); }
    public void putBoolean(String k, boolean v) { map.put(k, v); }
    public void putFloat(String k, float v) { map.put(k, v); }
    public void putDouble(String k, double v) { map.put(k, v); }
    public void putStringArray(String k, String[] v) { map.put(k, v); }

    public String getString(String k) { Object v = map.get(k); return v instanceof String ? (String) v : null; }
    public String getString(String k, String d) { String v = getString(k); return v == null ? d : v; }
    public int getInt(String k) { return getInt(k, 0); }
    public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
    public long getLong(String k) { return getLong(k, 0L); }
    public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Number ? ((Number) v).longValue() : d; }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Number ? ((Number) v).floatValue() : d; }
    public double getDouble(String k, double d) { Object v = map.get(k); return v instanceof Number ? ((Number) v).doubleValue() : d; }
    public String[] getStringArray(String k) { Object v = map.get(k); return v instanceof String[] ? (String[]) v : null; }

    public boolean containsKey(String k) { return map.containsKey(k); }
    public Set<String> keySet() { return map.keySet(); }
    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public void remove(String k) { map.remove(k); }
    public void clear() { map.clear(); }
}
