package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private PreferenceManager() { }

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return context.getSharedPreferences(getDefaultSharedPreferencesName(context), Context.MODE_PRIVATE);
    }

    public static String getDefaultSharedPreferencesName(Context context) {
        return context.getPackageName() + "_preferences";
    }
}
