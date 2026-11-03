package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Subset of upstream DataStore that plugins touch. Storage is the shim's SharedPreferences. */
object DataStore {
    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.getSharedPrefs(): SharedPreferences = getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)

    fun Context.getDefaultSharedPrefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
}
