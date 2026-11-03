package cloudstream.desktop.androidshim;

import android.content.SharedPreferences;
import cloudstream.desktop.contracts.KeyValueStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SharedPreferences backed by the contracts {@link KeyValueStore}. Values are written through
 * to the store immediately (commit and apply are both synchronous). Storage keys are
 * {@code shared_prefs/<file>/<key>}.
 */
public final class StoreBackedPreferences implements SharedPreferences {
    private final String file;
    private final List<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();

    public StoreBackedPreferences(String file) { this.file = file; }

    private KeyValueStore store() { return ShimRuntime.keyValueStore(); }
    private String prefix() { return "shared_prefs/" + file + "/"; }

    private Object raw(String key) {
        try {
            return store().get(prefix() + key, Object.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<String, ?> getAll() {
        Map<String, Object> m = new LinkedHashMap<>();
        String p = prefix();
        for (String k : store().keys(p)) {
            Object v = raw(k.substring(p.length()));
            if (v != null) m.put(k.substring(p.length()), v instanceof List ? new HashSet<>((List<?>) v) : v);
        }
        return m;
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = raw(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = raw(key);
        if (v instanceof Collection) {
            Set<String> s = new HashSet<>();
            for (Object o : (Collection<?>) v) s.add(String.valueOf(o));
            return s;
        }
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = raw(key);
        return v instanceof Number ? ((Number) v).intValue() : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = raw(key);
        return v instanceof Number ? ((Number) v).longValue() : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = raw(key);
        return v instanceof Number ? ((Number) v).floatValue() : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = raw(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return defValue;
    }

    @Override
    public boolean contains(String key) { return raw(key) != null; }

    @Override
    public Editor edit() { return new EditorImpl(); }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }

    private final class EditorImpl implements Editor {
        private final Map<String, Object> pending = new LinkedHashMap<>();
        private static final Object REMOVE = new Object();
        private boolean clear;

        @Override public Editor putString(String k, String v) { pending.put(k, v == null ? REMOVE : v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v == null ? REMOVE : new ArrayList<>(v)); return this; }
        @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        @Override public Editor remove(String k) { pending.put(k, REMOVE); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override
        public boolean commit() {
            List<String> changed = new ArrayList<>();
            String p = prefix();
            synchronized (StoreBackedPreferences.this) {
                if (clear) {
                    for (String k : store().keys(p)) {
                        store().remove(k);
                        changed.add(k.substring(p.length()));
                    }
                }
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == REMOVE) store().remove(p + e.getKey());
                    else store().set(p + e.getKey(), e.getValue());
                    changed.add(e.getKey());
                }
            }
            for (String k : changed) {
                for (OnSharedPreferenceChangeListener l : listeners) {
                    try { l.onSharedPreferenceChanged(StoreBackedPreferences.this, k); } catch (RuntimeException ignored) { }
                }
            }
            pending.clear();
            clear = false;
            return true;
        }

        @Override public void apply() { commit(); }
    }
}
