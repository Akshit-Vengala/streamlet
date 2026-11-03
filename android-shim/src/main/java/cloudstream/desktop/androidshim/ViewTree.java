package cloudstream.desktop.androidshim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Parent/child bookkeeping for the shimmed view classes (addView / removeView / getChildAt / getParent), so that a
 * dialog can tell which views - in particular which WebViews - it shows. Weak keys: views are never kept alive here.
 */
public final class ViewTree {
    private ViewTree() { }

    private static final Map<Object, List<Object>> CHILDREN = new WeakHashMap<>();
    private static final Map<Object, Object> PARENT = new WeakHashMap<>();

    public static synchronized void add(Object parent, Object child) {
        if (parent == null || child == null) return;
        Object old = PARENT.get(child);
        if (old != null) { List<Object> l = CHILDREN.get(old); if (l != null) l.remove(child); }
        CHILDREN.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
        PARENT.put(child, parent);
    }

    public static synchronized void remove(Object parent, Object child) {
        List<Object> l = CHILDREN.get(parent);
        if (l != null) l.remove(child);
        if (PARENT.get(child) == parent) PARENT.remove(child);
    }

    public static synchronized void clear(Object parent) {
        List<Object> l = CHILDREN.remove(parent);
        if (l != null) for (Object c : l) PARENT.remove(c);
    }

    public static synchronized int count(Object parent) {
        List<Object> l = CHILDREN.get(parent);
        return l == null ? 0 : l.size();
    }

    public static synchronized Object childAt(Object parent, int i) {
        List<Object> l = CHILDREN.get(parent);
        return l == null || i < 0 || i >= l.size() ? null : l.get(i);
    }

    public static synchronized Object parent(Object child) { return PARENT.get(child); }

    /** [root] and everything below it. */
    public static synchronized List<Object> descendants(Object root) {
        List<Object> out = new ArrayList<>();
        if (root == null) return out;
        out.add(root);
        for (int i = 0; i < out.size(); i++) {
            List<Object> l = CHILDREN.get(out.get(i));
            if (l != null) out.addAll(l);
        }
        return Collections.unmodifiableList(out);
    }
}
