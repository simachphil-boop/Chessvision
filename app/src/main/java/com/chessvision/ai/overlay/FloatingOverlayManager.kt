package com.chessvision.ai.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chessvision.ai.ui.theme.ChessVisionTheme
import kotlin.math.roundToInt

/** Lifecycle for a ComposeView that is attached to WindowManager instead of an Activity. */
internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() { savedState.performAttach(); savedState.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
}

/**
 * Adds/moves/resizes/removes the floating window via WindowManager (TYPE_APPLICATION_OVERLAY).
 * Holds only the service (application-scoped) context — no Activity is referenced.
 */
class FloatingOverlayManager(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    val compact = mutableStateOf(prefs.getBoolean("compact", false))
    /** Expanded width in px (board size follows it). */
    private var expandedWidth = prefs.getInt("width", (300 * density).roundToInt())
    private val compactWidth get() = (220 * density).roundToInt()

    val isShowing: Boolean get() = view != null

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    fun show(vm: OverlayViewModel, onClose: () -> Unit, onOpenApp: () -> Unit): Boolean {
        if (view != null) return true
        if (!OverlayPermissionManager.canDraw(context)) return false
        val lo = OverlayLifecycleOwner().also { it.create() }
        val cv = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lo)
            setViewTreeSavedStateRegistryOwner(lo)
            setViewTreeViewModelStoreOwner(lo)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ChessVisionTheme {
                    OverlayWindow(
                        vm = vm,
                        compact = compact.value,
                        onToggleCompact = { setCompact(!compact.value) },
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onResize = { dx -> resizeBy(dx) },
                        onClose = onClose,
                        onOpenApp = onOpenApp,
                    )
                }
            }
        }
        val (sw, sh) = screenSize()
        val p = WindowManager.LayoutParams(
            if (compact.value) compactWidth else expandedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", sw - width - (12 * density).roundToInt()).coerceIn(0, (sw - width).coerceAtLeast(0))
            y = prefs.getInt("y", (sh * 0.15f).roundToInt()).coerceIn(0, (sh - (120 * density).roundToInt()).coerceAtLeast(0))
        }
        return try {
            wm.addView(cv, p)
            lo.resume()
            view = cv; owner = lo; params = p
            true
        } catch (e: Exception) { // BadTokenException, SecurityException (permission revoked), IllegalStateException
            Log.e("Overlay", "addView failed", e)
            lo.destroy()
            false
        }
    }

    private fun apply() {
        val v = view ?: return
        val p = params ?: return
        try { wm.updateViewLayout(v, p) } catch (e: Exception) { Log.w("Overlay", "update failed", e) }
    }

    fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        val (sw, sh) = screenSize()
        val h = view?.height ?: 0
        p.x = (p.x + dx).roundToInt().coerceIn(0, (sw - p.width).coerceAtLeast(0))
        p.y = (p.y + dy).roundToInt().coerceIn(0, (sh - h.coerceAtLeast(48)).coerceAtLeast(0))
        apply()
        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
    }

    fun resizeBy(dx: Float) {
        val p = params ?: return
        if (compact.value) return
        val (sw, _) = screenSize()
        expandedWidth = (expandedWidth + dx).roundToInt().coerceIn((220 * density).roundToInt(), (sw - 8 * density).roundToInt().coerceAtLeast(200))
        p.width = expandedWidth
        p.x = p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
        apply()
        prefs.edit().putInt("width", expandedWidth).apply()
    }

    fun setCompact(c: Boolean) {
        compact.value = c
        val p = params ?: return
        p.width = if (c) compactWidth else expandedWidth
        val (sw, _) = screenSize()
        p.x = p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
        apply()
        prefs.edit().putBoolean("compact", c).apply()
    }

    /** After rotation the screen shrinks/grows: keep the window fully on screen. */
    fun clampToScreen() {
        val p = params ?: return
        val (sw, sh) = screenSize()
        if (!compact.value) { expandedWidth = expandedWidth.coerceAtMost((sw - 8 * density).roundToInt()); p.width = expandedWidth }
        p.x = p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (sh - (view?.height ?: 0).coerceAtLeast(48)).coerceAtLeast(0))
        apply()
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeViewImmediate(v) } catch (e: Exception) { Log.w("Overlay", "remove failed", e) }
        owner?.destroy()
        view = null; owner = null; params = null
    }
}
