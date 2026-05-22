package fxc.dev.ads_debug_kit

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class AdsDebugPanelView(
    context: Context,
    private val onClose: () -> Unit
) : LinearLayout(context), AdsDebugListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val renderRunnable = Runnable {
        isRenderPending = false
        render()
    }
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }
    private val tabBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(COLOR_HEADER_OVERLAY)
    }
    private var backgroundImageView: ImageView? = null
    private var selectedTab = Tab.STATES
    private var isRenderPending = false

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        buildLayout()
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AdsDebugKit.addListener(this)
        startBackgroundAnimation()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(renderRunnable)
        isRenderPending = false
        AdsDebugKit.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onAdsDebugChanged() {
        scheduleRender()
    }

    private fun buildLayout() {
        val panelContent = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        frame.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ads_debug_background)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = BACKGROUND_GIF_ALPHA
                backgroundImageView = this
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        panelContent.addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(8))
                setBackgroundColor(COLOR_HEADER_OVERLAY)

                addView(
                    TextView(context).apply {
                        text = "Ads Debug Kit"
                        setTextColor(COLOR_TEXT_PRIMARY)
                        textSize = 20f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                )

                addView(
                    TextView(context).apply {
                        text = "×"
                        gravity = Gravity.CENTER
                        setTextColor(COLOR_CLOSE_ICON)
                        textSize = 24f
                        setTypeface(typeface, Typeface.BOLD)
                        background = closeButtonDrawable()
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onClose() }
                    },
                    LayoutParams(dp(36), dp(36))
                )
            }
        )

        panelContent.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabBar)
            }
        )

        panelContent.addView(
            ScrollView(context).apply { addView(content) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        frame.addView(
            panelContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        addView(
            frame,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private fun startBackgroundAnimation() {
        backgroundImageView?.post {
            (backgroundImageView?.drawable as? Animatable)?.start()
        }
    }

    private fun render() {
        renderTabs()
        content.removeAllViews()
        when (selectedTab) {
            Tab.EVENTS -> renderEvents()
            Tab.STATES -> renderStates()
            Tab.SETTINGS -> renderSettings()
            Tab.AD_UNITS -> renderAdUnits()
            Tab.LOGS -> renderLogs()
        }
    }

    private fun scheduleRender() {
        if (isRenderPending) return
        isRenderPending = true
        mainHandler.postDelayed(renderRunnable, RENDER_DEBOUNCE_MS)
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        Tab.entries.forEach { tab ->
            val selected = tab == selectedTab
            tabBar.addView(
                TextView(context).apply {
                    text = tab.title
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) COLOR_TEXT_PRIMARY else COLOR_TEXT_SECONDARY)
                    textSize = 14f
                    setPadding(dp(14), 0, dp(14), 0)
                    background = tabButtonDrawable(selected)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedTab = tab
                        render()
                    }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)).apply {
                    marginEnd = dp(8)
                }
            )
        }
    }

    private fun renderEvents() {
        val events = AdsDebugKit.eventsSnapshot()
        addSectionTitle("Overview")
        addCard(
            title = "Revenue",
            lines = listOf(
                "totalMicros=${AdsDebugKit.totalRevenueMicros()}",
                "events=${events.size}"
            )
        )
        AdsDebugKit.revenueByNetwork().forEach { (network, valueMicros) ->
            addCard(title = network, lines = listOf("valueMicros=$valueMicros"))
        }
        addButton("Copy events JSON") {
            copyToClipboard("ads_debug_events", events.toJsonArray().toString())
        }

        addSectionTitle("Events (${events.size})")
        if (events.isEmpty()) {
            addEmpty("No ad events yet.")
            return
        }
        events.forEach { event ->
            addCard(
                title = "${formatTime(event.timestampMs)}  ${event.action.name}",
                lines = listOfNotNull(
                    "unit=${event.unit} placement=${event.placement}",
                    event.adUnitId?.let { "adUnit=$it" },
                    event.network?.let { "network=$it" },
                    event.lineItem?.let { "lineItem=$it" },
                    event.precision?.let { "precision=$it" },
                    event.message?.let { "message=$it" }
                )
            )
        }
    }

    private fun renderStates() {
        val states = AdsDebugKit.statesSnapshot()
        addSectionTitle("All IDs (${states.size})")
        if (states.isEmpty()) {
            addEmpty("No ad states yet.")
            return
        }
        states.forEach(::addStateCard)
    }

    private fun renderSettings() {
        addSectionTitle("Settings")
        addSwitch("Debug enabled", AdsDebugKit.settings.debugEnabled) { checked ->
            AdsDebugKit.setDebugEnabled(checked)
        }
        addSwitch("Show event toasts", AdsDebugKit.settings.showToasts) { checked ->
            AdsDebugKit.settings = AdsDebugKit.settings.copy(showToasts = checked)
        }
        addCard(
            title = "Keep events",
            lines = listOf("value=${AdsDebugKit.settings.keepEvents}")
        )
        addButton("Edit keep events") {
            showKeepEventsEditor()
        }
        addAdIdOverrideCard()
        addButton("Cycle mode") {
            val nextMode = when (AdsDebugKit.settings.adIdOverrideMode) {
                AdIdOverrideMode.NORMAL -> AdIdOverrideMode.FAIL_PRIMARY
                AdIdOverrideMode.FAIL_PRIMARY -> AdIdOverrideMode.FAIL_ALL
                AdIdOverrideMode.FAIL_ALL -> AdIdOverrideMode.FORCE_ADMOB_ONLY
                AdIdOverrideMode.FORCE_ADMOB_ONLY -> AdIdOverrideMode.CUSTOM
                AdIdOverrideMode.CUSTOM -> AdIdOverrideMode.NORMAL
            }
            AdsDebugKit.settings = AdsDebugKit.settings.copy(adIdOverrideMode = nextMode)
        }
        addButton("Clear events") {
            AdsDebugKit.clear()
        }
    }

    private fun renderAdUnits() {
        val adUnits = AdsDebugKit.allAdUnits()
        addSectionTitle("Ad Units (${adUnits.size})")
        if (adUnits.isEmpty()) {
            addEmpty("No ad units registered.")
            return
        }
        adUnits.forEach(::addAdUnitCard)
    }

    private fun renderLogs() {
        val lines = AdsDebugKit.debugLinesSnapshot()
        addSectionTitle("Logs (${lines.size})")
        if (lines.isEmpty()) {
            addEmpty("No debug lines yet.")
            return
        }
        lines.forEach { line -> addMonoText(line, line.externalStatusColor()) }
    }

    private fun showKeepEventsEditor() {
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AdsDebugKit.settings.keepEvents.toString())
            setSelection(text.length)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle("Keep Events")
            .setMessage("Number of events/log lines to keep (1-1000)")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text?.toString()?.toIntOrNull()?.coerceIn(1, 1000) ?: return@setPositiveButton
                AdsDebugKit.settings = AdsDebugKit.settings.copy(keepEvents = value)
            }
            .show()
    }

    private fun showAdIdOverrideInfo() {
        var okButton: TextView? = null
        val dialog = AlertDialog.Builder(context)
            .setView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(20), dp(18), dp(20), dp(12))
                    setBackgroundColor(COLOR_CARD)
                    addView(
                        TextView(context).apply {
                            text = "Ad ID override modes"
                            setTextColor(COLOR_TEXT_PRIMARY)
                            textSize = 18f
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    )
                    addView(
                        TextView(context).apply {
                            text = listOf(
                                "NORMAL: use app configured IDs.",
                                "FAIL_PRIMARY: only 2F/MF priority placements use invalid ID; normal and AdMob-only IDs stay configured.",
                                "FAIL_ALL: all requests use invalid ID.",
                                "FORCE_ADMOB_ONLY: every non-AdMob-only placement uses invalid ID; only *_admob_only_id stays configured.",
                                "CUSTOM: Ad Units tab shows Release / Debug / False buttons per placement.",
                                "Custom Release uses the app configured ID. Debug uses Google test ID by ad format. False uses /0000000000."
                            ).joinToString("\n\n")
                            setTextColor(COLOR_TEXT_SECONDARY)
                            textSize = 14f
                            setPadding(0, dp(14), 0, dp(10))
                        }
                    )
                    addView(
                        TextView(context).apply {
                            text = "OK"
                            gravity = Gravity.CENTER
                            setTextColor(COLOR_STATUS_LOADING)
                            textSize = 14f
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(dp(16), dp(10), dp(16), dp(10))
                            okButton = this
                        },
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.END
                        }
                    )
                }
            )
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(COLOR_CARD)
            })
            okButton?.setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        android.widget.Toast.makeText(context, "Copied $label", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun addSectionTitle(text: String) {
        content.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
        )
    }

    private fun addEmpty(text: String) {
        content.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(COLOR_TEXT_SECONDARY)
                textSize = 14f
            }
        )
    }

    private fun addSwitch(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        var isCheckedState = checked
        lateinit var thumb: View
        lateinit var track: FrameLayout

        fun renderSwitch(animate: Boolean) {
            track.background = switchTrackDrawable(isCheckedState)
            val targetX = if (isCheckedState) dp(22).toFloat() else 0f
            thumb.animate().cancel()
            if (animate) {
                thumb.animate()
                    .translationX(targetX)
                    .setDuration(SWITCH_ANIMATION_MS)
                    .start()
            } else {
                thumb.translationX = targetX
            }
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true

            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(COLOR_TEXT_PRIMARY)
                    textSize = 16f
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )

            addView(
                FrameLayout(context).apply {
                    track = this
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    addView(
                        View(context).apply {
                            thumb = this
                            background = switchThumbDrawable()
                            elevation = dp(2).toFloat()
                        },
                        FrameLayout.LayoutParams(dp(20), dp(20))
                    )
                },
                LayoutParams(dp(48), dp(26))
            )

            setOnClickListener {
                isCheckedState = !isCheckedState
                renderSwitch(animate = true)
                onCheckedChange(isCheckedState)
            }
        }
        renderSwitch(animate = false)
        content.addView(row)
    }

    private fun addButton(text: String, onClick: View.OnClickListener) {
        content.addView(
            TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                background = actionButtonDrawable()
                isClickable = true
                isFocusable = true
                setOnClickListener(onClick)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addAdIdOverrideCard() {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = "Ad ID override"
                        setTextColor(COLOR_TEXT_PRIMARY)
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    TextView(context).apply {
                        text = "i"
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(COLOR_TEXT_PRIMARY)
                        background = circleDrawable(COLOR_INFO_BUTTON)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { showAdIdOverrideInfo() }
                    },
                    LayoutParams(dp(28), dp(28))
                )
            }
        )
        listOf(
            "mode=${AdsDebugKit.settings.adIdOverrideMode}",
            "Cycle: normal/fail-primary/fail-all/force-admob-only/custom."
        ).forEach { line ->
            card.addView(
                TextView(context).apply {
                    text = line
                    setTextColor(COLOR_TEXT_SECONDARY)
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
        content.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addAdUnitCard(adUnit: AdDebugAdUnit) {
        val selectedMode = AdsDebugKit.displayModeFor(adUnit)
        val resolvedAdUnitId = AdsDebugKit.resolvedAdUnitIdForDisplay(adUnit)
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(
            TextView(context).apply {
                text = adUnit.name
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        listOf(
            "unit=${adUnit.unit}",
            "adUnit=$resolvedAdUnitId",
            if (adUnit.unit == AdDebugUnit.APP_ID) {
                "readOnly=manifest_app_id"
            } else {
                "appliedMode=$selectedMode"
            }
        ).forEach { line ->
            card.addView(
                TextView(context).apply {
                    text = line
                    setTextColor(COLOR_TEXT_SECONDARY)
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
        if (adUnit.unit != AdDebugUnit.APP_ID) {
            card.addView(adUnitOverrideButtons(adUnit, selectedMode))
        }
        content.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun adUnitOverrideButtons(adUnit: AdDebugAdUnit, selectedMode: AdUnitCustomMode): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(8), 0, 0)
            addOverrideModeButton("Release", adUnit, AdUnitCustomMode.RELEASE, selectedMode)
            addOverrideModeButton("Debug", adUnit, AdUnitCustomMode.DEBUG, selectedMode)
            addOverrideModeButton("False", adUnit, AdUnitCustomMode.FALSE, selectedMode)
        }
    }

    private fun LinearLayout.addOverrideModeButton(
        text: String,
        adUnit: AdDebugAdUnit,
        mode: AdUnitCustomMode,
        selectedMode: AdUnitCustomMode
    ) {
        val selected = mode == selectedMode
        addView(
            TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(mode.textColor())
                background = modeButtonDrawable(selected, mode.textColor())
                alpha = if (selected) 1f else 0.72f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AdsDebugKit.setCustomAdUnitMode(adUnit.name, mode)
                }
            },
            LayoutParams(dp(76), dp(30)).apply {
                marginEnd = dp(8)
            }
        )
    }

    private fun addCard(title: String, lines: List<String>) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(
            TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        lines.forEach { line ->
            card.addView(
                TextView(context).apply {
                    text = line
                    setTextColor(COLOR_TEXT_SECONDARY)
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
        content.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addStateCard(state: AdDebugState) {
        val stateColor = state.loadState.statusColor()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(
            TextView(context).apply {
                text = state.placement
                setTextColor(stateColor)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        state.adUnitId?.takeIf { it.isNotBlank() }?.let { adUnitId ->
            card.addView(
                TextView(context).apply {
                    text = adUnitId
                    setTextColor(stateColor)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
        card.addView(
            TextView(context).apply {
                text = state.compactStatusLine()
                setTextColor(COLOR_TEXT_SECONDARY)
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
        )
        content.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addMonoText(text: String, color: Int = COLOR_TEXT_SECONDARY) {
        content.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            }
        )
    }

    private fun formatTime(timestampMs: Long): String = timeFormatter.format(Date(timestampMs))

    private fun AdDebugState.compactStatusLine(): SpannableString {
        val loadValue = loadLabel()
        val showValue = showLabel()
        val revenueValue = revenueLabel()
        val line = "Load: $loadValue • Show/impression: $showValue • Rev: $revenueValue"
        val loadStart = "Load: ".length
        val showStart = loadStart + loadValue.length + " • Show/impression: ".length
        return SpannableString(line).apply {
            colorRange(loadStart, loadValue.length, loadState.statusColor())
            colorRange(showStart, showValue.length, showState.statusColor())
        }
    }

    private fun AdDebugState.loadLabel(): String {
        return when (loadState) {
            AdDebugLoadState.NOT_LOADED -> "No"
            AdDebugLoadState.LOADING -> "Loading"
            AdDebugLoadState.SUCCESS -> if (successCount > 0) "Success($successCount)" else "Success"
            AdDebugLoadState.FAILED -> if (failedCount > 0) "Failed($failedCount)" else "Failed"
        }
    }

    private fun AdDebugState.showLabel(): String {
        return when {
            showedCount > 0 -> "Yes($showedCount)"
            showState == AdDebugShowState.SHOWING -> "Loading"
            showState == AdDebugShowState.FAILED -> "Failed"
            else -> "No"
        }
    }

    private fun AdDebugState.revenueLabel(): String {
        val value = revenueMicros / 1_000_000.0
        val currency = currencyCode?.takeIf { it.isNotBlank() }
        return if (currency == null || currency == "USD") {
            "$" + String.format(Locale.US, "%.4f", value)
        } else {
            "$currency " + String.format(Locale.US, "%.4f", value)
        }
    }

    private fun SpannableString.colorRange(start: Int, length: Int, color: Int) {
        if (start < 0 || length <= 0 || start + length > this.length) return
        setSpan(
            ForegroundColorSpan(color),
            start,
            start + length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun AdDebugLoadState.statusColor(): Int {
        return when (this) {
            AdDebugLoadState.SUCCESS -> COLOR_STATUS_SUCCESS
            AdDebugLoadState.FAILED -> COLOR_STATUS_FAILED
            AdDebugLoadState.LOADING -> COLOR_STATUS_LOADING
            AdDebugLoadState.NOT_LOADED -> COLOR_TEXT_SECONDARY
        }
    }

    private fun AdDebugShowState.statusColor(): Int {
        return when (this) {
            AdDebugShowState.SHOWN -> COLOR_STATUS_SUCCESS
            AdDebugShowState.FAILED -> COLOR_STATUS_FAILED
            AdDebugShowState.SHOWING -> COLOR_STATUS_LOADING
            AdDebugShowState.NOT_SHOWN -> COLOR_TEXT_SECONDARY
        }
    }

    private fun AdUnitCustomMode.textColor(): Int {
        return when (this) {
            AdUnitCustomMode.RELEASE -> COLOR_STATUS_SUCCESS
            AdUnitCustomMode.DEBUG -> COLOR_STATUS_LOADING
            AdUnitCustomMode.FALSE -> COLOR_STATUS_FAILED
            AdUnitCustomMode.ADMOB_ONLY -> COLOR_TEXT_SECONDARY
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), COLOR_INFO_BUTTON_STROKE)
        }
    }

    private fun closeButtonDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_BUTTON_BACKGROUND)
            setStroke(dp(1), COLOR_BUTTON_STROKE)
        }
    }

    private fun tabButtonDrawable(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (selected) COLOR_TAB_SELECTED else COLOR_BUTTON_BACKGROUND)
            setStroke(dp(1), if (selected) COLOR_BUTTON_STROKE_ACTIVE else COLOR_BUTTON_STROKE)
        }
    }

    private fun actionButtonDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(COLOR_BUTTON_BACKGROUND)
            setStroke(dp(1), COLOR_BUTTON_STROKE)
        }
    }

    private fun modeButtonDrawable(selected: Boolean, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(if (selected) COLOR_MODE_BUTTON_SELECTED else COLOR_MODE_BUTTON_BACKGROUND)
            setStroke(dp(1), if (selected) color else COLOR_BUTTON_STROKE)
        }
    }

    private fun switchTrackDrawable(checked: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(if (checked) COLOR_SWITCH_TRACK_ON else COLOR_SWITCH_TRACK_OFF)
            setStroke(dp(1), if (checked) COLOR_STATUS_SUCCESS else COLOR_INFO_BUTTON_STROKE)
        }
    }

    private fun switchThumbDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_SWITCH_THUMB)
        }
    }

    private fun String.externalStatusColor(): Int {
        return when {
            contains("status=${AdsDebugLogFormat.Status.SUCCESS}", ignoreCase = true) -> COLOR_STATUS_SUCCESS
            contains("status=${AdsDebugLogFormat.Status.FAILED}", ignoreCase = true) -> COLOR_STATUS_FAILED
            contains("Status_code_failure", ignoreCase = true) ||
                    contains("result=SERVER_ERROR", ignoreCase = true) ||
                    contains("result=NO_CONNECTIVITY", ignoreCase = true) -> COLOR_STATUS_FAILED
            contains("tracked", ignoreCase = true) ||
                    contains("track", ignoreCase = true) ||
                    contains("success", ignoreCase = true) ||
                    contains("transaction_id", ignoreCase = true) ||
                    contains("failed=0", ignoreCase = true) -> COLOR_STATUS_SUCCESS
            contains("status=${AdsDebugLogFormat.Status.SUBMITTED}", ignoreCase = true) ||
                    contains("status=${AdsDebugLogFormat.Status.LOADING}", ignoreCase = true) -> COLOR_STATUS_LOADING
            else -> COLOR_TEXT_SECONDARY
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Tab(val title: String) {
        STATES("Ad States"),
        EVENTS("Ad Events"),
        LOGS("Externals"),
        SETTINGS("Settings"),
        AD_UNITS("Ad Units")
    }

    private fun List<AdDebugEvent>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { event ->
                array.put(
                    JSONObject()
                        .put("time", event.timestampMs)
                        .put("unit", event.unit.name)
                        .put("action", event.action.name)
                        .put("placement", event.placement)
                        .put("adUnitId", event.adUnitId)
                        .put("network", event.network)
                        .put("lineItem", event.lineItem)
                        .put("precision", event.precision)
                        .put("message", event.message)
                )
            }
        }
    }

    private companion object {
        const val COLOR_BACKGROUND = 0xFF111827.toInt()
        const val COLOR_HEADER = 0xFF0B1220.toInt()
        const val COLOR_HEADER_OVERLAY = 0x00000000
        const val COLOR_CARD = 0xBF1F2937.toInt()
        const val COLOR_TEXT_PRIMARY = 0xFFF9FAFB.toInt()
        const val COLOR_TEXT_SECONDARY = 0xFFD1D5DB.toInt()
        const val COLOR_STATUS_SUCCESS = 0xFF16A34A.toInt()
        const val COLOR_STATUS_FAILED = 0xFFE11D48.toInt()
        const val COLOR_STATUS_LOADING = 0xFFF59E0B.toInt()
        const val COLOR_CLOSE_ICON = 0xCCF9FAFB.toInt()
        const val COLOR_INFO_BUTTON = COLOR_CARD
        const val COLOR_INFO_BUTTON_STROKE = 0xFF4B5563.toInt()
        const val COLOR_BUTTON_BACKGROUND = 0xCC1F2937.toInt()
        const val COLOR_TAB_SELECTED = 0xE537455A.toInt()
        const val COLOR_BUTTON_STROKE = 0x807C8594.toInt()
        const val COLOR_BUTTON_STROKE_ACTIVE = 0xCCD1D5DB.toInt()
        const val COLOR_MODE_BUTTON_BACKGROUND = 0x8826323F.toInt()
        const val COLOR_MODE_BUTTON_SELECTED = 0xCC374151.toInt()
        const val COLOR_SWITCH_TRACK_ON = 0xCC14532D.toInt()
        const val COLOR_SWITCH_TRACK_OFF = 0xCC374151.toInt()
        const val COLOR_SWITCH_THUMB = 0xFFF9FAFB.toInt()
        const val BACKGROUND_GIF_ALPHA = 0.15f
        const val SWITCH_ANIMATION_MS = 120L
        const val RENDER_DEBOUNCE_MS = 80L
    }
}
