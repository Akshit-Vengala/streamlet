package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Off-screen rendered browser drawn by plain Swing (no OpenGL).
 *
 * <p>Why: JCEF's own OSR component is a JOGL GLCanvas, which fails on this machine ("Unable to determine
 * GraphicsConfiguration") and then drops every frame; and Chromium's windowed mode received no mouse input at all,
 * so a Turnstile checkbox could not be clicked. Here the frames CEF paints are copied into {@link #panel} and the
 * panel's mouse / keyboard events are sent to the browser. The same browser runs hidden (no copying) and can be put
 * in a window at any time without reloading its page.
 *
 * <p>In {@code org.cef.browser} (Java, not Kotlin) because JCEF's base class is package-private.
 */
public final class SwingOsrBrowser extends CefBrowser_N implements CefRenderHandler {
    private final Object lock = new Object();
    private volatile BufferedImage image;
    /** Copy frames only while the panel is on screen. */
    private volatile boolean painting;
    private final Dimension hiddenSize = new Dimension(1280, 800);
    private boolean created;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> paintListeners = new CopyOnWriteArrayList<>();

    private final JPanel panel = new JPanel() {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = image;
            if (img == null) return;
            synchronized (lock) { g.drawImage(img, 0, 0, getWidth(), getHeight(), null); }
        }
    };

    public SwingOsrBrowser(CefClient client, String url, CefRequestContext context) {
        super(client, url, context, null, null, null);
        panel.setBackground(Color.WHITE);
        panel.setFocusable(true);
        panel.setFocusTraversalKeysEnabled(false); // Tab goes to the page
        panel.setPreferredSize(new Dimension(1000, 700));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { panel.requestFocusInWindow(); sendMouseEvent(e); }
            @Override public void mouseReleased(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseMoved(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseDragged(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseEntered(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseExited(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseClicked(MouseEvent e) { sendMouseEvent(e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { sendMouseWheelEvent(e); }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
        panel.addMouseWheelListener(mouse);
        panel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { sendKeyEvent(e); }
            @Override public void keyReleased(KeyEvent e) { sendKeyEvent(e); }
            @Override public void keyTyped(KeyEvent e) { sendKeyEvent(e); }
        });
        panel.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { setFocus(true); }
            @Override public void focusLost(FocusEvent e) { setFocus(false); }
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (panel.getWidth() > 0 && panel.getHeight() > 0) wasResized(panel.getWidth(), panel.getHeight());
            }
        });
    }

    /** The surface that shows the page and takes its input. */
    public JPanel panel() { return panel; }

    /**
     * Mouse input from code (a plugin's simulated tap, Android {@code dispatchTouchEvent}): [action] 0 = down,
     * 1 = up, 2 = move, at view coordinates. Works hidden or shown; CEF delivers it as real (trusted) input.
     */
    public void injectMouse(int action, int x, int y) {
        long now = System.currentTimeMillis();
        int id; int mods; int button;
        switch (action) {
            case 0: id = MouseEvent.MOUSE_PRESSED; mods = java.awt.event.InputEvent.BUTTON1_DOWN_MASK; button = MouseEvent.BUTTON1; break;
            case 1: id = MouseEvent.MOUSE_RELEASED; mods = 0; button = MouseEvent.BUTTON1; break;
            default: id = MouseEvent.MOUSE_MOVED; mods = 0; button = MouseEvent.NOBUTTON; break;
        }
        if (action == 0) sendMouseEvent(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false, MouseEvent.NOBUTTON));
        sendMouseEvent(new MouseEvent(panel, id, now, mods, x, y, action == 2 ? 0 : 1, false, button));
    }

    /** Starts copying frames into the panel (it is about to be shown) or stops (hidden). */
    public void setPainting(boolean on) {
        painting = on;
        if (on) {
            if (panel.getWidth() > 0 && panel.getHeight() > 0) wasResized(panel.getWidth(), panel.getHeight());
            invalidate(); // a full repaint for the first frame
        }
    }

    @Override public synchronized void createImmediately() {
        if (created) return;
        created = true;
        createBrowser(getClient(), 0L, getUrl(), true, false, null, getRequestContext());
    }

    @Override public Component getUIComponent() { return panel; }
    @Override public CefRenderHandler getRenderHandler() { return this; }
    @Override protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context, CefBrowser_N parent, Point inspectAt) { return null; }
    @Override public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        BufferedImage img = image;
        return CompletableFuture.completedFuture(img != null ? img : new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
    }

    // ---- CefRenderHandler ----
    private Dimension viewSize() {
        return panel.getWidth() > 0 && panel.getHeight() > 0 ? new Dimension(panel.getWidth(), panel.getHeight()) : hiddenSize;
    }

    @Override public Rectangle getViewRect(CefBrowser browser) { Dimension d = viewSize(); return new Rectangle(0, 0, d.width, d.height); }

    @Override public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        double scale = 1.0;
        try { if (panel.getGraphicsConfiguration() != null) scale = panel.getGraphicsConfiguration().getDefaultTransform().getScaleX(); } catch (Throwable ignored) { }
        Dimension d = viewSize();
        Rectangle r = new Rectangle(0, 0, d.width, d.height);
        info.Set(scale, 32, 8, false, r, r);
        return true;
    }

    @Override public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        if (!panel.isShowing()) return new Point(viewPoint);
        try { Point o = panel.getLocationOnScreen(); return new Point(o.x + viewPoint.x, o.y + viewPoint.y); }
        catch (Throwable t) { return new Point(viewPoint); }
    }

    @Override public void onPopupShow(CefBrowser browser, boolean show) { }
    @Override public void onPopupSize(CefBrowser browser, Rectangle size) { }

    @Override public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (popup || !painting || width <= 0 || height <= 0) return;
        synchronized (lock) {
            BufferedImage img = image;
            if (img == null || img.getWidth() != width || img.getHeight() != height) {
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
                image = img;
            }
            int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            // CEF gives BGRA bytes: read as little-endian ints they are ARGB.
            ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            b.rewind();
            IntBuffer src = b.asIntBuffer();
            src.get(dst, 0, Math.min(dst.length, src.remaining()));
        }
        panel.repaint();
    }

    @Override public void addOnPaintListener(Consumer<CefPaintEvent> listener) { paintListeners.add(listener); }
    @Override public void setOnPaintListener(Consumer<CefPaintEvent> listener) { paintListeners.clear(); paintListeners.add(listener); }
    @Override public void removeOnPaintListener(Consumer<CefPaintEvent> listener) { paintListeners.remove(listener); }

    @Override public boolean onCursorChange(CefBrowser browser, int cursorType) {
        SwingUtilities.invokeLater(() -> { try { panel.setCursor(new Cursor(cursorType)); } catch (Throwable ignored) { } });
        return true;
    }

    @Override public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) { return false; }
    @Override public void updateDragCursor(CefBrowser browser, int operation) { }
}
