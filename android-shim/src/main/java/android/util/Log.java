package android.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    private static final Logger L = Logger.getLogger("android.util.Log");

    private Log() { }

    private static int log(Level lvl, String tag, String msg, Throwable t) {
        if (L.isLoggable(lvl)) L.log(lvl, "[" + tag + "] " + msg, t);
        return 0;
    }

    public static int v(String tag, String msg) { return log(Level.FINER, tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return log(Level.FINER, tag, msg, t); }
    public static int d(String tag, String msg) { return log(Level.FINE, tag, msg, null); }
    public static int d(String tag, String msg, Throwable t) { return log(Level.FINE, tag, msg, t); }
    public static int i(String tag, String msg) { return log(Level.INFO, tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return log(Level.INFO, tag, msg, t); }
    public static int w(String tag, String msg) { return log(Level.WARNING, tag, msg, null); }
    public static int w(String tag, Throwable t) { return log(Level.WARNING, tag, "", t); }
    public static int w(String tag, String msg, Throwable t) { return log(Level.WARNING, tag, msg, t); }
    public static int e(String tag, String msg) { return log(Level.SEVERE, tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return log(Level.SEVERE, tag, msg, t); }
    public static int wtf(String tag, String msg) { return log(Level.SEVERE, tag, msg, null); }
    public static boolean isLoggable(String tag, int level) { return true; }

    public static String getStackTraceString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        if (t != null) t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
