package fxc.dev.ads_debug_kit

import android.app.Application
import android.app.Activity
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
        val storedSettings = prefs.settings
        settingsCache = storedSettings
        discoveredAdUnits = AdUnitResourceDiscoverer.discover(appContext, config)
        rebuildAdUnitCache(discoverIfMissing = false)
        settingsCache = storedSettings.normalizedForCurrentConfig()
        if (settingsCache != storedSettings) {
            prefs.settings = settingsCache
        }
        (appContext as? Application)?.let { AdsDebugWindowManager.initialize(it) }
        configureDebugServices(settings.debugEnabled)
        notifyChanged()
    }

    internal fun currentConfig(): AdDebugConfig = config

    fun isInitialized(): Boolean = ::appContext.isInitialized

    fun isDebugEnabled(): Boolean = isInitialized() && settingsCache.isEffectivelyDebugEnabled()

    var settings: AdDebugSettings
        get() = if (::prefs.isInitialized) {
            settingsCache.copy(debugEnabled = settingsCache.isEffectivelyDebugEnabled())
        } else {
            AdDebugSettings()
        }
        set(value) {
            ensureInitialized()
            val previousSettings = settingsCache
            val normalizedValue = value
                .normalizedForCurrentConfig()
                .let { requestedSettings ->
                    if (config.forceDebugEnabled) {
                        requestedSettings.copy(debugEnabled = previousSettings.debugEnabled)
                    } else {
                        requestedSettings
                    }
                }
            settingsCache = normalizedValue
            prefs.settings = normalizedValue
            val wasDebugEnabled = previousSettings.isEffectivelyDebugEnabled()
            val isDebugEnabled = normalizedValue.isEffectivelyDebugEnabled()
            if (wasDebugEnabled != isDebugEnabled) {
                configureDebugServices(isDebugEnabled)
            }
            if (
                config.mediationProvider == AdMediationProvider.APPLOVIN_MAX &&
                (
                    wasDebugEnabled != isDebugEnabled ||
                        previousSettings.adIdOverrideMode != normalizedValue.adIdOverrideMode ||
                        previousSettings.customAdUnitModes != normalizedValue.customAdUnitModes
                    )
            ) {
                showToast("Restart app to apply MAX and tracking debug settings")
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
        // Preserve the legacy contract for callers that resolve before initialize().
        if (!isInitialized()) return primaryAdUnitId
        if (
            config.allowAdUnitOverrides &&
            isDebugEnabled() &&
            config.mediationProvider == AdMediationProvider.ADMOB &&
            role == AdIdRequestRole.PRIMARY &&
            isOverridablePlacement(placement) &&
            !primaryAdUnitId.isAppIdValue() &&
            settings.adIdOverrideMode == AdIdOverrideMode.CUSTOM &&
            customAdUnitMode(placement) == AdUnitCustomMode.ADMOB_ONLY
        ) {
            val legacyFallbackId = admobOnlyAdUnitId ?: legacyAdmobOnlyPeerId(placement)
            if (legacyFallbackId != null) {
                return resolveProviderAdUnitId(
                    placement = placement,
                    configuredAdUnitId = legacyFallbackId,
                    role = AdProviderRequestRole.PROVIDER_ONLY,
                )
            }
        }
        val configuredAdUnitId = when (role) {
            AdIdRequestRole.PRIMARY -> primaryAdUnitId
            AdIdRequestRole.ADMOB_ONLY -> admobOnlyAdUnitId ?: primaryAdUnitId
        }
        return resolveProviderAdUnitId(
            placement = placement,
            configuredAdUnitId = configuredAdUnitId,
            role = when (role) {
                AdIdRequestRole.PRIMARY -> AdProviderRequestRole.PRIMARY
                AdIdRequestRole.ADMOB_ONLY -> AdProviderRequestRole.PROVIDER_ONLY
            },
        )
    }

    /**
     * Resolves the configured ID for the request currently being made. A primary request and a
     * provider-only fallback request must be sent separately by the host app.
     */
    fun resolveProviderAdUnitId(
        placement: String,
        configuredAdUnitId: String,
        role: AdProviderRequestRole = AdProviderRequestRole.PRIMARY,
    ): String {
        if (!isInitialized()) return configuredAdUnitId
        val placementMatch = providerOnlyPlacementMatch(placement)
        val adUnit = adUnitCache.byPlacement[placement]
        val resolved = AdUnitIdOverrideResolver.resolve(
            AdUnitIdResolutionInput(
                configuredAdUnitId = configuredAdUnitId,
                invalidAdUnitId = invalidAdUnitIdFor(configuredAdUnitId, placement),
                debugAdUnitId = debugAdUnitIdFor(adUnit, configuredAdUnitId),
                overrideMode = settings.adIdOverrideMode,
                customMode = customAdUnitMode(placement),
                requestRole = role,
                providerOnlyPlacementMatch = placementMatch,
                isPriorityPlacement = placement.isPriorityPlacement(),
                overridesEnabled = config.allowAdUnitOverrides,
                debugEnabled = isDebugEnabled(),
                overridable = isOverridablePlacement(placement) && !configuredAdUnitId.isAppIdValue(),
            )
        )
        if (
            resolved != configuredAdUnitId ||
            role == AdProviderRequestRole.PROVIDER_ONLY ||
            placementMatch == ProviderOnlyPlacementMatch.CURRENT_PROVIDER
        ) {
            logAdIdDebugEvent(
                placement = placement,
                message = "AdIdResolver mode=${overrideModeLabel(settings.adIdOverrideMode)} " +
                    "role=$role resolved=$resolved"
            )
        }
        return resolved
    }

    fun setCustomAdUnitMode(placement: String, mode: AdUnitCustomMode) {
        ensureInitialized()
        if (!config.allowAdUnitOverrides) return
        if (!isOverridablePlacement(placement)) {
            logAdIdDebugEvent(
                placement = placement,
                message = "AdIdCustomMode skipped=read_only"
            )
            return
        }
        val currentSettings = settings
        val previousModes = if (currentSettings.adIdOverrideMode == AdIdOverrideMode.CUSTOM) {
            currentSettings.customAdUnitModes
                .filterKeys(::isOverridablePlacement)
        } else {
            snapshotDisplayModes(currentSettings.adIdOverrideMode)
        }
        val currentModes = customModesAfterSelection(
            currentModes = previousModes,
            placement = placement,
            selectedMode = mode,
            selectedUnit = adUnitCache.byPlacement[placement]?.unit,
            providerOnlyUnits = providerOnlyUnitsByPlacement(),
        )
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
        if (!config.allowAdUnitOverrides) return AdUnitCustomMode.RELEASE
        return settings.customAdUnitModes[placement] ?: AdUnitCustomMode.RELEASE
    }

    internal fun resolvedAdUnitIdForDisplay(adUnit: AdDebugAdUnit): String {
        val placementMatch = providerOnlyPlacementMatch(adUnit.name)
        return AdUnitIdOverrideResolver.resolve(
            AdUnitIdResolutionInput(
                configuredAdUnitId = adUnit.adUnitId,
                invalidAdUnitId = invalidAdUnitIdFor(adUnit),
                debugAdUnitId = debugAdUnitIdFor(adUnit, adUnit.adUnitId),
                overrideMode = settings.adIdOverrideMode,
                customMode = customAdUnitMode(adUnit.name),
                requestRole = if (placementMatch == ProviderOnlyPlacementMatch.CURRENT_PROVIDER) {
                    AdProviderRequestRole.PROVIDER_ONLY
                } else {
                    AdProviderRequestRole.PRIMARY
                },
                providerOnlyPlacementMatch = placementMatch,
                isPriorityPlacement = adUnit.name.isPriorityPlacement(),
                overridesEnabled = config.allowAdUnitOverrides,
                debugEnabled = isDebugEnabled(),
                overridable = adUnit.isOverridable(),
            )
        )
    }

    internal fun displayModeFor(adUnit: AdDebugAdUnit): AdUnitCustomMode {
        if (!config.allowAdUnitOverrides) return AdUnitCustomMode.RELEASE
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
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> if (
                providerOnlyPlacementMatch(adUnit.name) == ProviderOnlyPlacementMatch.CURRENT_PROVIDER
            ) {
                AdUnitCustomMode.RELEASE
            } else {
                AdUnitCustomMode.FALSE
            }
        }
    }

    internal fun hasProviderOnlyFallback(): Boolean {
        if (!isInitialized()) return false
        return providerOnlyUnitsByPlacement().isNotEmpty()
    }

    internal fun hasProviderOnlyFallbackFor(unit: AdDebugUnit): Boolean {
        return providerOnlyUnitsByPlacement().any { (_, providerOnlyUnit) ->
            providerOnlyUnit == unit
        }
    }

    internal fun providerOnlyFallbackUnits(): Set<AdDebugUnit> {
        return providerOnlyUnitsByPlacement().values.toSet()
    }

    internal fun isProviderOnlyAdUnit(adUnit: AdDebugAdUnit): Boolean {
        return providerOnlyPlacementMatch(adUnit.name) == ProviderOnlyPlacementMatch.CURRENT_PROVIDER
    }

    internal fun overrideModeLabel(mode: AdIdOverrideMode): String {
        return if (mode == AdIdOverrideMode.FORCE_ADMOB_ONLY) "FORCE_FALLBACK" else mode.name
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

    internal fun executeToolAction(
        activity: Activity,
        toolAction: AdDebugToolAction,
        closePanel: () -> Unit
    ) {
        val closeResult = runCatching(closePanel).onFailure { error ->
            logToolFailure(toolAction, error)
        }
        if (closeResult.isFailure) return

        mainHandler.postDelayed({
            runCatching { toolAction.action(activity) }.onFailure { error ->
                logToolFailure(toolAction, error)
            }
        }, TOOL_ACTION_DELAY_MILLIS)
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

    private fun debugAdUnitIdFor(adUnit: AdDebugAdUnit?, configuredAdUnitId: String): String {
        return when (config.mediationProvider) {
            AdMediationProvider.ADMOB -> debugAdUnitIdFor(adUnit?.unit ?: AdDebugUnit.OTHER)
            // MAX has no reusable sample ad-unit IDs. Its Test Mode uses the configured unit.
            AdMediationProvider.APPLOVIN_MAX -> configuredAdUnitId
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

    private fun invalidAdUnitIdFor(adUnit: AdDebugAdUnit): String {
        return invalidAdUnitIdFor(adUnit.adUnitId, adUnit.name)
    }

    private fun invalidAdUnitIdFor(primaryAdUnitId: String, placement: String): String {
        val cachedAdUnit = adUnitCache.byPlacement[placement]
        if (cachedAdUnit?.unit == AdDebugUnit.APP_ID || primaryAdUnitId.isAppIdValue()) {
            return primaryAdUnitId
        }
        return when (config.mediationProvider) {
            AdMediationProvider.ADMOB -> config.invalidAdUnitId
            AdMediationProvider.APPLOVIN_MAX -> {
                val configuredInvalidId = config.invalidAdUnitId
                    .takeUnless { it == DEFAULT_ADMOB_INVALID_AD_UNIT_ID }
                configuredInvalidId ?: maxInvalidAdUnitIdFor(placement)
            }
        }
    }

    private fun AdDebugAdUnit.isOverridable(): Boolean {
        return config.allowAdUnitOverrides && unit != AdDebugUnit.APP_ID
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

    private fun maxInvalidAdUnitIdFor(placement: String): String {
        val placementHash = placement.hashCode().toUInt().toString(16).padStart(8, '0')
        return MAX_INVALID_AD_UNIT_PREFIX + placementHash
    }

    private fun providerOnlyPlacementMatch(placement: String): ProviderOnlyPlacementMatch {
        val provider = providerOnlyProviderFor(placement, config.adUnitResourceSuffix)
            ?: return ProviderOnlyPlacementMatch.NONE
        return if (provider == config.mediationProvider) {
            ProviderOnlyPlacementMatch.CURRENT_PROVIDER
        } else {
            ProviderOnlyPlacementMatch.OTHER_PROVIDER
        }
    }

    private fun providerOnlyUnitsByPlacement(): Map<String, AdDebugUnit> {
        return adUnitCache.units
            .asSequence()
            .filter { adUnit ->
                providerOnlyPlacementMatch(adUnit.name) == ProviderOnlyPlacementMatch.CURRENT_PROVIDER
            }
            .associate { adUnit -> adUnit.name to adUnit.unit }
    }

    private fun legacyAdmobOnlyPeerId(placement: String): String? {
        if (!placement.endsWith(config.adUnitResourceSuffix)) return null
        val fallbackPlacement = placement.removeSuffix(config.adUnitResourceSuffix) +
            "_admob_only" + config.adUnitResourceSuffix
        return adUnitCache.byPlacement[fallbackPlacement]?.adUnitId
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

    private fun logToolFailure(toolAction: AdDebugToolAction, error: Throwable) {
        val message = buildString {
            append("Tool action ")
            append(toolAction.id)
            append(" failed: ")
            append(error::class.java.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
        }
        logDebugLine(message)
        showToast("${toolAction.title} failed")
    }

    private fun AdDebugSettings.normalizedForCurrentConfig(): AdDebugSettings {
        return normalizeAdDebugSettingsForCapabilities(
            settings = this,
            allowAdUnitOverrides = config.allowAdUnitOverrides,
            providerOnlyUnits = providerOnlyFallbackUnits(),
            unitByPlacement = adUnitCache.units.associate { adUnit -> adUnit.name to adUnit.unit },
        )
    }

    private fun AdDebugSettings.isEffectivelyDebugEnabled(): Boolean {
        return config.forceDebugEnabled || debugEnabled
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
            AdDebugAction.EXPIRED -> AdDebugLoadState.NOT_LOADED
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
    )

    private const val TOOL_ACTION_DELAY_MILLIS = 220L
    private const val DEFAULT_ADMOB_INVALID_AD_UNIT_ID = "ca-app-pub-3940256099942544/0000000000"
    private const val MAX_INVALID_AD_UNIT_PREFIX = "ffffffff"
}
