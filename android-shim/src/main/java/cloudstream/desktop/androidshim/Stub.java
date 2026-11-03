package cloudstream.desktop.androidshim;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Log-once helper used by every generated stub. Also records which stubs plugins actually touched. */
public final class Stub {
    private static final Logger LOG = Logger.getLogger("android-shim");
    private static final Set<String> HITS = ConcurrentHashMap.newKeySet();

    private Stub() { }

    /** Called by unimplemented methods. Logs the FQN of the method once. */
    public static void hit(String fqn) {
        if (HITS.add(fqn)) {
            LOG.fine("[android-shim] unimplemented on desktop: " + fqn);
            if (ShimRuntime.isStubLoggingVerbose()) {
                LOG.warning("[android-shim] unimplemented on desktop: " + fqn);
            }
        }
    }

    /** Every distinct unimplemented member invoked so far (FQN.member). */
    public static Set<String> hits() {
        return Collections.unmodifiableSet(HITS);
    }

    /** True if any UI (view/widget/fragment/dialog) stub has been touched. Hint that a plugin tried to show Android UI. */
    public static boolean uiTouched() {
        for (String h : HITS) {
            if (h.startsWith("android.view.") || h.startsWith("android.widget.") || h.startsWith("androidx.fragment.")
                    || h.startsWith("android.app.") || h.startsWith("androidx.appcompat.") || h.startsWith("androidx.recyclerview.")) {
                return true;
            }
        }
        return false;
    }
}
