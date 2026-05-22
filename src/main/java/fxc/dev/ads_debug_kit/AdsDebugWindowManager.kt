package fxc.dev.ads_debug_kit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.lang.ref.WeakReference

internal object AdsDebugWindowManager : Application.ActivityLifecycleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var isPanelVisible = false
    private var currentActivityRef: WeakReference<Activity>? = null
    private var panelActivityRef: WeakReference<Activity>? = null
    private var panelViews: PanelViews? = null

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun show() {
        mainHandler.post {
            isPanelVisible = true
            attachPanelToCurrentActivity(allowFallbackActivity = true)
        }
    }

    fun hide() {
        mainHandler.post {
            isPanelVisible = false
            detachPanel(animate = true)
        }
    }

    fun toggle() {
        mainHandler.post {
            if (isPanelVisible) {
                isPanelVisible = false
                detachPanel(animate = true)
            } else {
                isPanelVisible = true
                attachPanelToCurrentActivity(allowFallbackActivity = true)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is AdsDebugActivity) return
        currentActivityRef = WeakReference(activity)
        if (isPanelVisible) {
            attachPanelToCurrentActivity(allowFallbackActivity = false)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            currentActivityRef = null
        }
        if (panelActivityRef?.get() === activity) {
            detachPanel(animate = false)
            if (isPanelVisible) {
                attachPanelToCurrentActivity(allowFallbackActivity = false)
            }
        }
    }

    private fun attachPanelToCurrentActivity(allowFallbackActivity: Boolean) {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            if (allowFallbackActivity) {
                AdsDebugKit.startFallbackActivity()
            }
            return
        }

        if (panelActivityRef?.get() === activity && panelViews?.overlay?.parent != null) return

        detachPanel(animate = false)
        val root = activity.window.decorView as? ViewGroup ?: return
        val views = createPanelViews(activity) { hide() }

        root.addView(
            views.overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panelActivityRef = WeakReference(activity)
        panelViews = views
        animatePanelIn(views)
    }

    internal fun createStandaloneContent(activity: Activity, onClose: () -> Unit): View {
        val views = createPanelViews(activity, onClose)
        views.dim.alpha = 1f
        return views.overlay
    }

    private fun createPanelViews(activity: Activity, onClose: () -> Unit): PanelViews {
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = false
            elevation = PANEL_ELEVATION
        }
        val dim = View(activity).apply {
            setBackgroundColor(COLOR_DIM)
            alpha = 0f
        }
        val panel = AdsDebugPanelView(activity, onClose).apply {
            isClickable = true
            isFocusable = true
            elevation = PANEL_ELEVATION
        }

        overlay.addView(
            dim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = topSheetMargin(activity)
            }
        )
        return PanelViews(overlay = overlay, dim = dim, panel = panel)
    }

    private fun animatePanelIn(views: PanelViews) {
        views.overlay.post {
            val startTranslation = overlayHeight(views.overlay).toFloat()
            views.panel.translationY = startTranslation
            views.dim.alpha = 0f
            views.dim.animate()
                .alpha(1f)
                .setDuration(DIM_ANIMATION_MS)
                .start()
            views.panel.animate()
                .translationY(0f)
                .setDuration(SHEET_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun detachPanel(animate: Boolean) {
        val views = panelViews ?: return
        panelActivityRef = null
        panelViews = null

        fun removeOverlay() {
            (views.overlay.parent as? ViewGroup)?.removeView(views.overlay)
        }

        if (!animate || views.overlay.parent == null) {
            removeOverlay()
            return
        }

        views.dim.animate()
            .alpha(0f)
            .setDuration(DIM_ANIMATION_MS)
            .start()
        views.panel.animate()
            .translationY(overlayHeight(views.overlay).toFloat())
            .setDuration(SHEET_ANIMATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { removeOverlay() }
            .start()
    }

    @Suppress("DEPRECATION")
    private fun topSheetMargin(activity: Activity): Int {
        val topInset = activity.window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0
        return maxOf(topInset, dp(activity, MIN_TOP_INSET_DP))
    }

    private fun overlayHeight(overlay: View): Int {
        return overlay.height.takeIf { it > 0 } ?: overlay.resources.displayMetrics.heightPixels
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private const val PANEL_ELEVATION = 10_000f
    private const val COLOR_DIM = 0x66000000
    private const val MIN_TOP_INSET_DP = 24
    private const val DIM_ANIMATION_MS = 140L
    private const val SHEET_ANIMATION_MS = 190L

    private data class PanelViews(
        val overlay: FrameLayout,
        val dim: View,
        val panel: View
    )
}
