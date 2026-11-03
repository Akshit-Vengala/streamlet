// Hand-written (replaces the generated stub, whose obtain() returned null): a plugin's simulated tap
// (a plugin's Turnstile auto-click: obtain + WebView.dispatchTouchEvent) must carry its action and position.
package android.view;

public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_MASK = 0xff;

    private final long downTime;
    private final long eventTime;
    private final int action;
    private final float x;
    private final float y;
    private final int metaState;

    public MotionEvent() { this(0, 0, ACTION_CANCEL, 0f, 0f, 0); }

    private MotionEvent(long downTime, long eventTime, int action, float x, float y, int metaState) {
        this.downTime = downTime; this.eventTime = eventTime; this.action = action; this.x = x; this.y = y; this.metaState = metaState;
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) {
        return new MotionEvent(downTime, eventTime, action, x, y, metaState);
    }

    public int getAction() { return action; }
    public int getActionMasked() { return action & ACTION_MASK; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getRawX() { return x; }
    public float getRawY() { return y; }
    public long getDownTime() { return downTime; }
    public long getEventTime() { return eventTime; }
    public int getMetaState() { return metaState; }
    public int getPointerCount() { return 1; }
    public void recycle() { }
}
