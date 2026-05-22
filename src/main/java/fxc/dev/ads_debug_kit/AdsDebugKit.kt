package fxc.dev.ads_debug_kit

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object AdsDebugKit {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<AdsDebugListener>()
    private val logTimeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val notifyRunnable = Runnable {
        listeners.forEach { it.onAdsDebugChanged() }
    }

    private lateinit var appContext: Context
    private lateinit var prefs: AdsDebugPrefs

    @Volatile
    private var config = AdDebugConfig()

    @Volatile
    private var settingsCache = AdDebugSettings()
    private val lock = Any()
    private val events = mutableListOf<AdDebugEvent>()
    private val revenues = mutableListOf<AdDebugRevenueEvent>()
    private val customEvents = mutableListOf<AdDebugCustomEvent>()
    private val debugLines = mutableListOf<String>()
    private val states = linkedMapOf<String, AdDebugState>()
    @Volatile
    private var discoveredAdUnits: List<AdDebugAdUnit> = emptyList()

    @Volatile
    private var adUnitCache = AdUnitCache()

    @Volatile
    private var isAdUnitCacheInitialized = false

    fun initialize(context: Context, config: AdDebugConfig = AdDebugConfig()) {
        appContext = context.applicationContext
        prefs = AdsDebugPrefs(appContext)
        this.config = config
        settingsCache = prefs.settings
        discoveredAdUnits = AdUnitResourceDiscoverer.discover(appContext, config)
        rebuildAdUnitCache(discoverIfMissing = false)
        (appContext as? Application)?.let { AdsDebugWindowManager.initialize(it) }
        configureDebugServices(settings.debugEnabled)
        notifyChanged()
    }

    internal fun currentConfig(): AdDebugConfig = config

    fun isInitialized(): Boolean = ::appContext.isInitialized

    fun isDebugEnabled(): Boolean = isInitialized() && settings.debugEnabled

    var settings: AdDebugSettings
        get() = if (::prefs.isInitialized) settingsCache else AdDebugSettings()
        set(value) {
            ensureInitialized()
            val previousSettings = settingsCache
            settingsCache = value
            prefs.settings = value
            if (previousSettings.debugEnabled != value.debugEnabled) {
                configureDebugServices(value.debugEnabled)
            }
            notifyChanged()
        }

    fun setDebugEnabled(enabled: Boolean) {
        settings = settings.copy(debugEnabled = enabled)
    }

    internal fun log(event: AdDebugEvent) {
        if (!isDebugEnabled()) return
        synchronized(lock) {
            events.add(0, event)
            trim(events)
            updateAdState(event)
        }
        if (settings.showToasts) showToast("${event.unit.name.lowercase()} • ${event.action.name.lowercase()}")
        notifyChanged()
    }

    internal fun logRevenue(event: AdDebugRevenueEvent) {
        if (!isDebugEnabled()) return
        synchronized(lock) {
            revenues.add(0, event)
            trim(revenues)
            addRevenue(event)
        }
        if (settings.showToasts) showToast("Revenue ${event.unit.name.lowercase()} +${event.currencyCode} ${event.valueMicros}")
        notifyChanged()
    }

    internal fun logCustomEvent(event: AdDebugCustomEvent) {
        if (!isDebugEnabled()) return
        synchronized(lock) {
            customEvents.add(0, event)
            trim(customEvents)
        }
        if (settings.showToasts) showToast("Custom ${event.event}")
        notifyChanged()
    }

    internal fun logDebugLine(line: String) {
        logDebugLines(listOf(line))
    }

    internal fun logDebugLines(lines: List<String>) {
        if (!isDebugEnabled()) return
        if (lines.isEmpty()) return
        synchronized(lock) {
            val timestamp = logTimeFormatter.format(Date())
            lines.asReversed().forEach { line ->
                debugLines.add(0, "[$timestamp] $line")
            }
            trim(debugLines)
        }
        notifyChanged()
    }

    fun allAdUnits(): List<AdDebugAdUnit> {
        if (!isInitialized()) return emptyList()
        if (!isAdUnitCacheInitialized) {
            rebuildAdUnitCache(discoverIfMissing = discoveredAdUnits.isEmpty())
        }
        return adUnitCache.units
    }

    fun eventsSnapshot(): List<AdDebugEvent> = synchronized(lock) { events.toList() }

    fun revenuesSnapshot(): List<AdDebugRevenueEvent> = synchronized(lock) { revenues.toList() }

    fun customEventsSnapshot(): List<AdDebugCustomEvent> = synchronized(lock) { customEvents.toList() }

    fun debugLinesSnapshot(): List<String> = synchronized(lock) { debugLines.toList() }

    fun statesSnapshot(): List<AdDebugState> = synchronized(lock) {
        val adUnitPlacements = allAdUnits().mapTo(linkedSetOf()) { it.name }
        val orderedStates = adUnitCache.units.map { adUnit ->
            states[adUnit.name] ?: adUnit.toDefaultState()
        }
        val extraStates = states.values.filter { state ->
            state.placement !in adUnitPlacements && state.adUnitId.hasKnownAdUnitId()
        }
        orderedStates + extraStates
    }

    fun resolvePlacementName(adUnitId: String?, fallback: String): String {
        if (!isInitialized() || adUnitId.isNullOrBlank()) return fallback
        return adUnitCache.byAdUnitId[adUnitId]?.name ?: fallback
    }

    fun totalRevenueMicros(): Long = synchronized(lock) {
        revenues.sumOf { it.valueMicros }
    }

    fun revenueByNetwork(): List<Pair<String, Long>> = synchronized(lock) {
        revenues
            .groupBy { it.network?.takeIf { network -> network.isNotBlank() } ?: "unknown" }
            .mapValues { entry -> entry.value.sumOf { it.valueMicros } }
            .toList()
            .sortedByDescending { it.second }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            revenues.clear()
            customEvents.clear()
            debugLines.clear()
            states.clear()
        }
        notifyChanged()
    }

    fun resolveAdUnitId(
        placement: String,
        primaryAdUnitId: String,
        admobOnlyAdUnitId: String? = null,
        role: AdIdRequestRole = AdIdRequestRole.PRIMARY
    ): String {
        if (!isInitialized()) return primaryAdUnitId
        if (!settings.debugEnabled) {
            return when (role) {
                AdIdRequestRole.PRIMARY -> primaryAdUnitId
                AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
            }
        }
        if (!isOverridablePlacement(placement) || primaryAdUnitId.isAppIdValue()) return primaryAdUnitId
        val resolved = when (settings.adIdOverrideMode) {
            AdIdOverrideMode.NORMAL -> when (role) {
                AdIdRequestRole.PRIMARY -> primaryAdUnitId
                AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
            }

            AdIdOverrideMode.FAIL_PRIMARY -> when (role) {
                AdIdRequestRole.PRIMARY -> if (placement.isPriorityPlacement()) {
                    invalidAdUnitIdFor(primaryAdUnitId, placement)
                } else {
                    primaryAdUnitId
                }
                AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
            }

            AdIdOverrideMode.FAIL_ALL -> invalidAdUnitIdFor(primaryAdUnitId, placement)
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> when {
                placement.isAdmobOnlyPlacement() -> primaryAdUnitId
                role == AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
                else -> invalidAdUnitIdFor(primaryAdUnitId, placement)
            }
            AdIdOverrideMode.CUSTOM -> resolveCustomAdUnitId(
                placement = placement,
                primaryAdUnitId = primaryAdUnitId,
                admobOnlyAdUnitId = admobOnlyAdUnitId,
                role = role
            )
        }
        if (resolved != primaryAdUnitId || role == AdIdRequestRole.ADMOB_ONLY) {
            logAdIdDebugEvent(
                placement = placement,
                message = "AdIdResolver mode=${settings.adIdOverrideMode} role=$role resolved=$resolved"
            )
        }
        return resolved
    }

    fun setCustomAdUnitMode(placement: String, mode: AdUnitCustomMode) {
        ensureInitialized()
        if (!isOverridablePlacement(placement)) {
            logAdIdDebugEvent(
                placement = placement,
                message = "AdIdCustomMode skipped=read_only"
            )
            return
        }
        val currentSettings = settings
        val currentModes = if (currentSettings.adIdOverrideMode == AdIdOverrideMode.CUSTOM) {
            currentSettings.customAdUnitModes
                .filterKeys(::isOverridablePlacement)
                .toMutableMap()
        } else {
            snapshotDisplayModes(currentSettings.adIdOverrideMode).toMutableMap()
        }

        currentModes[placement] = mode
        val allRelease = currentModes.values.all { it == AdUnitCustomMode.RELEASE }
        settings = currentSettings.copy(
            adIdOverrideMode = if (allRelease) AdIdOverrideMode.NORMAL else AdIdOverrideMode.CUSTOM,
            customAdUnitModes = if (allRelease) emptyMap() else currentModes
        )
        logAdIdDebugEvent(
            placement = placement,
            message = "AdIdCustomMode mode=$mode"
        )
    }

    fun customAdUnitMode(placement: String): AdUnitCustomMode {
        return settings.customAdUnitModes[placement] ?: AdUnitCustomMode.RELEASE
    }

    internal fun resolvedAdUnitIdForDisplay(adUnit: AdDebugAdUnit): String {
        if (!adUnit.isOverridable()) return adUnit.adUnitId
        return when (settings.adIdOverrideMode) {
            AdIdOverrideMode.NORMAL -> adUnit.adUnitId
            AdIdOverrideMode.FAIL_PRIMARY -> if (adUnit.name.isPriorityPlacement()) {
                invalidAdUnitIdFor(adUnit)
            } else {
                adUnit.adUnitId
            }
            AdIdOverrideMode.FAIL_ALL -> invalidAdUnitIdFor(adUnit)
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> if (adUnit.name.isAdmobOnlyPlacement()) {
                adUnit.adUnitId
            } else {
                invalidAdUnitIdFor(adUnit)
            }
            AdIdOverrideMode.CUSTOM -> when (customAdUnitMode(adUnit.name)) {
                AdUnitCustomMode.RELEASE -> adUnit.adUnitId
                AdUnitCustomMode.DEBUG -> debugAdUnitIdFor(adUnit)
                AdUnitCustomMode.FALSE -> invalidAdUnitIdFor(adUnit)
                AdUnitCustomMode.ADMOB_ONLY -> admobOnlyDisplayIdFor(adUnit)
            }
        }
    }

    internal fun displayModeFor(adUnit: AdDebugAdUnit): AdUnitCustomMode {
        return displayModeFor(adUnit, settings.adIdOverrideMode)
    }

    private fun displayModeFor(adUnit: AdDebugAdUnit, mode: AdIdOverrideMode): AdUnitCustomMode {
        if (!adUnit.isOverridable()) return AdUnitCustomMode.RELEASE
        return when (mode) {
            AdIdOverrideMode.NORMAL -> AdUnitCustomMode.RELEASE
            AdIdOverrideMode.FAIL_PRIMARY -> if (adUnit.name.isPriorityPlacement()) {
                AdUnitCustomMode.FALSE
            } else {
                AdUnitCustomMode.RELEASE
            }
            AdIdOverrideMode.FAIL_ALL -> AdUnitCustomMode.FALSE
            AdIdOverrideMode.CUSTOM -> customAdUnitMode(adUnit.name)
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> if (adUnit.name.isAdmobOnlyPlacement()) {
                AdUnitCustomMode.RELEASE
            } else {
                AdUnitCustomMode.FALSE
            }
        }
    }

    fun show() {
        ensureInitialized()
        AdsDebugWindowManager.show()
    }

    fun hide() {
        ensureInitialized()
        AdsDebugWindowManager.hide()
    }

    fun toggle() {
        ensureInitialized()
        AdsDebugWindowManager.toggle()
    }

    internal fun startFallbackActivity() {
        if (!isInitialized()) return
        val intent = Intent(appContext, AdsDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    fun addListener(listener: AdsDebugListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AdsDebugListener) {
        listeners.remove(listener)
    }

    internal fun notifyChanged() {
        if (listeners.isEmpty()) return
        mainHandler.removeCallbacks(notifyRunnable)
        mainHandler.post(notifyRunnable)
    }

    internal fun debugAdUnitIdFor(unit: AdDebugUnit): String {
        return when (unit) {
            AdDebugUnit.APP_ID -> config.invalidAdUnitId
            AdDebugUnit.NATIVE -> "ca-app-pub-3940256099942544/2247696110"
            AdDebugUnit.INTERSTITIAL -> "ca-app-pub-3940256099942544/1033173712"
            AdDebugUnit.REWARDED -> "ca-app-pub-3940256099942544/5224354917"
            AdDebugUnit.APP_OPEN -> "ca-app-pub-3940256099942544/9257395921"
            AdDebugUnit.BANNER -> "ca-app-pub-3940256099942544/6300978111"
            AdDebugUnit.OTHER -> config.invalidAdUnitId
        }
    }

    private fun resolveCustomAdUnitId(
        placement: String,
        primaryAdUnitId: String,
        admobOnlyAdUnitId: String?,
        role: AdIdRequestRole
    ): String {
        val adUnit = adUnitCache.byPlacement[placement]
        val mode = customAdUnitMode(placement)
        return when (mode) {
            AdUnitCustomMode.RELEASE -> when (role) {
                AdIdRequestRole.PRIMARY -> primaryAdUnitId
                AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
            }

            AdUnitCustomMode.DEBUG -> {
                if (adUnit?.unit == AdDebugUnit.APP_ID || primaryAdUnitId.isAppIdValue()) {
                    adUnit?.adUnitId ?: primaryAdUnitId
                } else {
                    debugAdUnitIdFor(adUnit?.unit ?: AdDebugUnit.OTHER)
                }
            }

            AdUnitCustomMode.FALSE -> invalidAdUnitIdFor(primaryAdUnitId, placement)
            AdUnitCustomMode.ADMOB_ONLY -> admobOnlyAdUnitId
                ?: adUnit?.let { admobOnlyDisplayIdFor(it) }
                ?: primaryAdUnitId
        }
    }

    private fun rebuildAdUnitCache(discoverIfMissing: Boolean) {
        if (discoverIfMissing && discoveredAdUnits.isEmpty()) {
            discoveredAdUnits = AdUnitResourceDiscoverer.discover(appContext, config)
        }

        val merged = linkedMapOf<String, AdDebugAdUnit>()
        discoveredAdUnits.forEach { merged[it.name] = it }
        runCatching { config.allAdUnits() }
            .getOrDefault(emptyList())
            .forEach { merged[it.name] = it }

        val units = merged.values.sortedByResourceOrder()
        val byAdUnitId = linkedMapOf<String, AdDebugAdUnit>()
        units.forEach { adUnit ->
            byAdUnitId.putIfAbsent(adUnit.adUnitId, adUnit)
        }
        adUnitCache = AdUnitCache(
            units = units,
            byPlacement = units.associateBy { it.name },
            byAdUnitId = byAdUnitId,
            admobOnlyByPlacement = units.associateWithAdmobOnlyPlacement()
        )
        isAdUnitCacheInitialized = true
    }

    private fun snapshotDisplayModes(mode: AdIdOverrideMode): Map<String, AdUnitCustomMode> {
        return allAdUnits()
            .filter { it.isOverridable() }
            .associate { adUnit ->
                adUnit.name to displayModeFor(adUnit, mode)
            }
    }

    private fun debugAdUnitIdFor(adUnit: AdDebugAdUnit): String {
        return if (adUnit.unit == AdDebugUnit.APP_ID) {
            adUnit.adUnitId
        } else {
            debugAdUnitIdFor(adUnit.unit)
        }
    }

    private fun invalidAdUnitIdFor(adUnit: AdDebugAdUnit): String {
        return invalidAdUnitIdFor(adUnit.adUnitId, adUnit.name)
    }

    private fun invalidAdUnitIdFor(primaryAdUnitId: String, placement: String): String {
        val cachedAdUnit = adUnitCache.byPlacement[placement]
        if (cachedAdUnit?.unit == AdDebugUnit.APP_ID || primaryAdUnitId.isAppIdValue()) {
            return primaryAdUnitId
        }
        return config.invalidAdUnitId
    }

    private fun AdDebugAdUnit.isOverridable(): Boolean {
        return unit != AdDebugUnit.APP_ID
    }

    private fun isOverridablePlacement(placement: String): Boolean {
        return adUnitCache.byPlacement[placement]?.isOverridable() ?: true
    }

    private fun Collection<AdDebugAdUnit>.sortedByResourceOrder(): List<AdDebugAdUnit> {
        return sortedWith(
            compareBy<AdDebugAdUnit> { if (it.resourceId != 0) it.resourceId else Int.MAX_VALUE }
                .thenBy { it.name }
        )
    }

    private fun admobOnlyDisplayIdFor(adUnit: AdDebugAdUnit): String {
        if (adUnit.unit == AdDebugUnit.APP_ID) return adUnit.adUnitId
        return adUnitCache.admobOnlyByPlacement[adUnit.name]?.adUnitId ?: adUnit.adUnitId
    }

    private fun List<AdDebugAdUnit>.associateWithAdmobOnlyPlacement(): Map<String, AdDebugAdUnit> {
        val byPlacement = associateBy { it.name }
        return buildMap {
            this@associateWithAdmobOnlyPlacement.forEach { adUnit ->
                val admobOnlyPlacement = adUnit.name.admobOnlyPeerPlacement() ?: return@forEach
                byPlacement[admobOnlyPlacement]?.let { admobOnlyAdUnit ->
                    put(adUnit.name, admobOnlyAdUnit)
                }
            }
        }
    }

    private fun String.admobOnlyPeerPlacement(): String? {
        if (isAdmobOnlyPlacement() || !endsWith(config.adUnitResourceSuffix)) return null
        return removeSuffix(config.adUnitResourceSuffix) + "_admob_only" + config.adUnitResourceSuffix
    }

    private fun String.isAdmobOnlyPlacement(): Boolean {
        return contains("_admob_only") && endsWith(config.adUnitResourceSuffix)
    }

    private fun String.isPriorityPlacement(): Boolean {
        val normalizedName = lowercase()
        return normalizedName.endsWith("_2f${config.adUnitResourceSuffix}") ||
                normalizedName.endsWith("_mf${config.adUnitResourceSuffix}")
    }

    private fun String.isAppIdValue(): Boolean {
        return contains("~") && !contains("/")
    }

    private fun ensureInitialized() {
        check(isInitialized()) { "AdsDebugKit.initialize(context) must be called before using AdsDebugKit." }
    }

    private fun showToast(message: String) {
        if (!isInitialized()) return
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureDebugServices(enabled: Boolean) {
        if (enabled) {
            AdsDebugTimberTree.start()
            ExternalLogTap.start()
            MotionShakeDetector.start(appContext) {
                toggle()
            }
        } else {
            AdsDebugTimberTree.stop()
            ExternalLogTap.stop()
            MotionShakeDetector.stop()
            AdsDebugWindowManager.hide()
        }
    }

    private fun updateAdState(event: AdDebugEvent) {
        val placement = normalizePlacement(event.placement, event.adUnitId)
        val currentState = states[placement] ?: AdDebugState(
            placement = placement,
            unit = event.unit,
            adUnitId = event.adUnitId,
            loadState = AdDebugLoadState.NOT_LOADED,
            showState = AdDebugShowState.NOT_SHOWN,
            lastAction = event.action,
            network = event.network,
            message = event.message,
            successCount = 0,
            failedCount = 0,
            showedCount = 0,
            revenueMicros = 0L,
            currencyCode = null,
            updatedAtMs = event.timestampMs
        )

        val loadState = when (event.action) {
            AdDebugAction.LOAD_START -> AdDebugLoadState.LOADING
            AdDebugAction.LOAD_SUCCESS -> AdDebugLoadState.SUCCESS
            AdDebugAction.LOAD_FAIL -> AdDebugLoadState.FAILED
            else -> currentState.loadState
        }
        val showState = when (event.action) {
            AdDebugAction.SHOW_START -> AdDebugShowState.SHOWING
            AdDebugAction.SHOW_SUCCESS,
            AdDebugAction.SHOW_DISMISSED,
            AdDebugAction.IMPRESSION -> AdDebugShowState.SHOWN
            AdDebugAction.SHOW_FAIL -> AdDebugShowState.FAILED
            else -> currentState.showState
        }

        states[placement] = currentState.copy(
            unit = event.unit,
            adUnitId = event.adUnitId ?: currentState.adUnitId,
            loadState = loadState,
            showState = showState,
            lastAction = event.action,
            network = event.network ?: currentState.network,
            message = event.message,
            successCount = currentState.successCount + if (event.action == AdDebugAction.LOAD_SUCCESS) 1 else 0,
            failedCount = currentState.failedCount + if (event.action == AdDebugAction.LOAD_FAIL) 1 else 0,
            showedCount = currentState.showedCount + if (event.shouldIncreaseShowCount()) 1 else 0,
            updatedAtMs = event.timestampMs
        )
    }

    private fun logAdIdDebugEvent(placement: String, message: String) {
        log(
            AdDebugEvent(
                unit = adUnitCache.byPlacement[placement]?.unit ?: AdDebugUnit.OTHER,
                action = AdDebugAction.DEBUG,
                placement = placement,
                adUnitId = adUnitCache.byPlacement[placement]?.adUnitId,
                message = message
            )
        )
    }

    private fun AdDebugEvent.shouldIncreaseShowCount(): Boolean {
        return when (action) {
            AdDebugAction.SHOW_SUCCESS -> true
            AdDebugAction.IMPRESSION -> unit == AdDebugUnit.NATIVE || unit == AdDebugUnit.BANNER
            else -> false
        }
    }

    private fun addRevenue(event: AdDebugRevenueEvent) {
        val placement = normalizePlacement(event.placement, event.adUnitId)
        val currentState = states[placement] ?: AdDebugState(
            placement = placement,
            unit = event.unit,
            adUnitId = event.adUnitId,
            loadState = AdDebugLoadState.NOT_LOADED,
            showState = AdDebugShowState.NOT_SHOWN,
            lastAction = AdDebugAction.DEBUG,
            network = event.network,
            message = null,
            successCount = 0,
            failedCount = 0,
            showedCount = 0,
            revenueMicros = 0L,
            currencyCode = event.currencyCode,
            updatedAtMs = event.timestampMs
        )
        states[placement] = currentState.copy(
            unit = event.unit,
            adUnitId = event.adUnitId ?: currentState.adUnitId,
            network = event.network ?: currentState.network,
            revenueMicros = currentState.revenueMicros + event.valueMicros,
            currencyCode = event.currencyCode,
            updatedAtMs = event.timestampMs
        )
    }

    private fun normalizePlacement(placement: String, adUnitId: String?): String {
        if (placement.startsWith(config.adUnitResourcePrefix) && placement.endsWith(config.adUnitResourceSuffix)) {
            return placement
        }
        if (!adUnitId.isNullOrBlank()) {
            adUnitCache.byAdUnitId[adUnitId]?.let { return it.name }
        }
        return placement
    }

    private fun String?.hasKnownAdUnitId(): Boolean {
        if (isNullOrBlank()) return false
        return adUnitCache.byAdUnitId.containsKey(this)
    }

    private fun <T> trim(list: MutableList<T>) {
        val keepEvents = settings.keepEvents
        while (list.size > keepEvents) {
            list.removeAt(list.lastIndex)
        }
    }

    private fun AdDebugAdUnit.toDefaultState(): AdDebugState {
        return AdDebugState(
            placement = name,
            unit = unit,
            adUnitId = adUnitId,
            loadState = AdDebugLoadState.NOT_LOADED,
            showState = AdDebugShowState.NOT_SHOWN,
            lastAction = AdDebugAction.DEBUG,
            network = null,
            message = null,
            successCount = 0,
            failedCount = 0,
            showedCount = 0,
            revenueMicros = 0L,
            currencyCode = null,
            updatedAtMs = 0L
        )
    }

    private data class AdUnitCache(
        val units: List<AdDebugAdUnit> = emptyList(),
        val byPlacement: Map<String, AdDebugAdUnit> = emptyMap(),
        val byAdUnitId: Map<String, AdDebugAdUnit> = emptyMap(),
        val admobOnlyByPlacement: Map<String, AdDebugAdUnit> = emptyMap()
    )
}
