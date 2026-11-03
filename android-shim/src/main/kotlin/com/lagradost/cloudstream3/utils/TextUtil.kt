package com.lagradost.cloudstream3.utils

import android.content.Context

sealed class UiText {
    companion object {
        const val TAG = "UiText"
    }

    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(val resId: Int, val args: List<Any>) : UiText() {
        override fun toString(): String = "resId = $resId\nargs = ${args.toList()}"
        override fun equals(other: Any?): Boolean =
            other is StringResource && resId == other.resId && args == other.args

        override fun hashCode(): Int = 31 * resId + args.hashCode()
    }

    fun asStringNull(context: Context?): String? {
        return try {
            asString(context ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            // Android string resources do not exist on desktop.
            is StringResource -> context.getString(resId)
        }
    }
}

fun txt(value: String): UiText = UiText.DynamicString(value)

@JvmName("txtNull")
fun txt(value: String?): UiText? = UiText.DynamicString(value ?: return null)

fun txt(resId: Int, vararg args: Any): UiText = UiText.StringResource(resId, args.toList())
