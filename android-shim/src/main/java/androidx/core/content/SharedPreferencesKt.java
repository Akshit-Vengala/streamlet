package androidx.core.content;

import android.content.SharedPreferences;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class SharedPreferencesKt {
    private SharedPreferencesKt() { }

    public static void edit(SharedPreferences prefs, boolean commit, Function1<? super SharedPreferences.Editor, Unit> action) {
        SharedPreferences.Editor e = prefs.edit();
        action.invoke(e);
        if (commit) e.commit(); else e.apply();
    }

    public static void edit$default(SharedPreferences prefs, boolean commit, Function1<? super SharedPreferences.Editor, Unit> action, int mask, Object unused) {
        if ((mask & 1) != 0) commit = false;
        edit(prefs, commit, action);
    }
}
