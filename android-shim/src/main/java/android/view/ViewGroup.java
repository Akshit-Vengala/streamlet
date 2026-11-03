// Hand-written (generator skips it): children are recorded in ViewTree so dialogs know which WebViews they show.
package android.view;

import cloudstream.desktop.androidshim.ViewTree;

public class ViewGroup extends android.view.View implements android.view.ViewParent {
    public ViewGroup() { }

    public void addView(android.view.View child) { ViewTree.add(this, child); }
    public void addView(android.view.View child, android.view.ViewGroup.LayoutParams params) { ViewTree.add(this, child); }
    public void addView(android.view.View child, int index) { ViewTree.add(this, child); }
    public void addView(android.view.View child, int index, android.view.ViewGroup.LayoutParams params) { ViewTree.add(this, child); }
    public void addView(android.view.View child, int width, int height) { ViewTree.add(this, child); }
    public void removeView(android.view.View child) { ViewTree.remove(this, child); }
    public void removeAllViews() { ViewTree.clear(this); }
    public int getChildCount() { return ViewTree.count(this); }
    public android.view.View getChildAt(int index) {
        Object c = ViewTree.childAt(this, index);
        return c instanceof android.view.View ? (android.view.View) c : new android.view.View();
    }
    public android.content.Context getContext() { return cloudstream.desktop.androidshim.ShimRuntime.context(); }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width = 0;
        public int height = 0;
        public LayoutParams() { }
        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
    }
    public static class MarginLayoutParams extends android.view.ViewGroup.LayoutParams {
        public MarginLayoutParams() { }
        public MarginLayoutParams(int width, int height) { super(width, height); }
    }
}
