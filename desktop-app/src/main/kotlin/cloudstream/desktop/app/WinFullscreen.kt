package cloudstream.desktop.app

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference

/**
 * Borderless fullscreen on Windows, the way browsers do it: drop the caption and frame, cover the monitor, and mark the
 * window full-screen for the taskbar (ITaskbarList2::MarkFullscreenWindow). Without the mark the taskbar stays on top
 * whenever another of our windows (the player controls overlay) is the active one, and Windows never treats a window
 * that still has a caption as full-screen. Must be called on the AWT event thread.
 */
object WinFullscreen {
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_NOOWNERZORDER = 0x0200

    private val log = AppLog.get("fullscreen")
    private val savedStyle = HashMap<Pointer, Int>()

    private fun hwnd(w: java.awt.Window): HWND? =
        runCatching { Native.getComponentPointer(w) }.getOrNull()?.let { HWND(it) }

    /** Enters fullscreen; returns false if it could not (the caller then falls back). */
    fun enter(w: java.awt.Window): Boolean = runCatching {
        val h = hwnd(w) ?: return false
        val u = User32.INSTANCE
        val style = u.GetWindowLong(h, WinUser.GWL_STYLE)
        savedStyle.putIfAbsent(h.pointer, style)
        u.SetWindowLong(h, WinUser.GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
        val info = WinUser.MONITORINFO()
        u.GetMonitorInfo(u.MonitorFromWindow(h, WinUser.MONITOR_DEFAULTTONEAREST), info)
        val r = info.rcMonitor
        u.SetWindowPos(h, null, r.left, r.top, r.right - r.left, r.bottom - r.top,
            SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        markAll(w, true)
        true
    }.onFailure { log.warning("Fullscreen failed: $it") }.getOrDefault(false)

    /** Restores the caption and frame; the caller then puts the old bounds back. */
    fun exit(w: java.awt.Window) {
        runCatching {
            markAll(w, false)
            val h = hwnd(w) ?: return
            val style = savedStyle.remove(h.pointer) ?: return
            User32.INSTANCE.SetWindowLong(h, WinUser.GWL_STYLE, style)
            User32.INSTANCE.SetWindowPos(h, null, 0, 0, 0, 0,
                0x0001 /*NOSIZE*/ or 0x0002 /*NOMOVE*/ or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        }.onFailure { log.warning("Leaving fullscreen failed: $it") }
    }

    /** The main window and its owned windows (the controls overlay), so whichever is active keeps the taskbar away. */
    private fun markAll(w: java.awt.Window, on: Boolean) {
        (listOf(w) + w.ownedWindows.filter { it.isDisplayable }).forEach { win -> hwnd(win)?.let { mark(it, on) } }
    }

    // ITaskbarList2 vtable: IUnknown (0..2), HrInit 3, AddTab 4, DeleteTab 5, ActivateTab 6, SetActiveAlt 7, MarkFullscreenWindow 8.
    private val taskbar: Pointer? by lazy {
        runCatching {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED) // S_FALSE / RPC_E_CHANGED_MODE are fine
            val out = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Guid.GUID("{56FDF344-FD6D-11d0-958A-006097C9A090}"), null, 1 /*CLSCTX_INPROC_SERVER*/,
                Guid.GUID("{602D4995-B13A-429b-A66E-1935E44F4317}"), out,
            )
            check(hr.toInt() == 0) { "CoCreateInstance(TaskbarList) = 0x${Integer.toHexString(hr.toInt())}" }
            out.value.also { call(it, 3) }
        }.onFailure { log.warning("Taskbar fullscreen mark unavailable: $it") }.getOrNull()
    }

    private fun mark(h: HWND, on: Boolean) {
        val tb = taskbar ?: return
        call(tb, 8, h.pointer, if (on) 1 else 0)
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf(obj, *args))
    }
}
