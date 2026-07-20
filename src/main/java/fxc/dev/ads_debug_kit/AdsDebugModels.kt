package fxc.dev.ads_debug_kit

import android.app.Activity

enum class AdDebugUnit {
    APP_ID,
    NATIVE,
    INTERSTITIAL,
    REWARDED,
    APP_OPEN,
    BANNER,
    OTHER
}

/**
 * Identifies the mediation stack so DebugKit can resolve provider-specific test IDs without
 * coupling the host app to AdMob semantics. Keep AdMob as the default for existing consumers.
 */
enum class AdMediationProvider {
    ADMOB,
    APPLOVIN_MAX
}

enum class AdDebugAction {
    LOAD_START,
    LOAD_SUCCESS,
    LOAD_FAIL,
    SHOW_START,
    SHOW_SUCCESS,
    SHOW_FAIL,
    SHOW_DISMISSED,
    CLICK,
    IMPRESSION,
    POPULATE,
    FALLBACK,
    DEBUG,
    EXPIRED
}

enum class AdDebugLoadState {
    NOT_LOADED,
    LOADING,
    SUCCESS,
    FAILED
}

enum class AdDebugShowState {
    NOT_SHOWN,
    SHOWING,
    SHOWN,
    FAILED
}

enum class AdIdOverrideMode {
    NORMAL,
    FAIL_PRIMARY,
    FAIL_ALL,
    FORCE_ADMOB_ONLY,
    CUSTOM;

    companion object {
        /**
         * Provider-neutral source alias for the legacy enum entry. Keeping the backing enum entry
         * avoids breaking consumers that persist or exhaustively match [FORCE_ADMOB_ONLY].
         */
        @JvmField
        val FORCE_FALLBACK: AdIdOverrideMode = FORCE_ADMOB_ONLY
    }
}

enum class AdUnitCustomMode {
    RELEASE,
    DEBUG,
    FALSE,
    ADMOB_ONLY;

    companion object {
        /** Provider-neutral source alias for the legacy persisted value. */
        @JvmField
        val FALLBACK: AdUnitCustomMode = ADMOB_ONLY
    }
}

enum class AdIdRequestRole {
    PRIMARY,
    ADMOB_ONLY
}

/**
 * Identifies the configured ID passed for the request currently being made. DebugKit never starts
 * a fallback request or replaces a primary request with a provider-only ID; that remains the host
 * app's responsibility.
 */
enum class AdProviderRequestRole {
    PRIMARY,
    PROVIDER_ONLY
}

/**
 * Describes whether the host should send the resolved request to its mediation SDK. DebugKit uses
 * [FORCE_FAIL] for deterministic failure scenarios; the host must report the failure through its
 * normal callback path without constructing or loading an SDK ad object.
 */
enum class AdDebugRequestBehavior {
    ALLOW,
    FORCE_FAIL
}

data class AdDebugRequestResolution(
    val adUnitId: String,
    val behavior: AdDebugRequestBehavior = AdDebugRequestBehavior.ALLOW,
)

data class AdDebugAdUnit(
    val name: String,
    val adUnitId: String,
    val unit: AdDebugUnit = AdDebugUnit.OTHER,
    val resourceId: Int = 0
)

data class AdDebugToolAction(
    val id: String,
    val title: String,
    val description: String? = null,
    val action: (Activity) -> Unit
)

data class AdDebugConfig(
    val allAdUnits: () -> List<AdDebugAdUnit> = { emptyList() },
    val autoDiscoverAdUnits: Boolean = true,
    val enableDexResourceScan: Boolean = true,
    val resourceClassNames: List<String> = emptyList(),
    val adUnitResourcePrefix: String = "ads_",
    val adUnitResourceSuffix: String = "_id",
    val captureTimberLogs: Boolean = true,
    val mirrorTimberLogsToLogcat: Boolean = true,
    val timberLogTags: Set<String> = setOf(
        AdsDebugLogFormat.Tag.NATIVE,
        AdsDebugLogFormat.Tag.INTERSTITIAL,
        AdsDebugLogFormat.Tag.REWARDED,
        AdsDebugLogFormat.Tag.APP_OPEN,
        AdsDebugLogFormat.Tag.BANNER,
        AdsDebugLogFormat.Tag.REVENUE,
        AdsDebugLogFormat.Tag.EXTERNAL,
        AdsDebugLogFormat.Tag.CUSTOM,
        AdsDebugLogFormat.Tag.INIT,
        AdsDebugLogFormat.Tag.LIFECYCLE
    ),
    val invalidAdUnitId: String = "ca-app-pub-3940256099942544/0000000000",
    val backgroundDrawableResId: Int = R.drawable.ads_debug_background,
    val allowAdUnitOverrides: Boolean = true,
    val toolActions: List<AdDebugToolAction> = emptyList(),
    val mediationProvider: AdMediationProvider = AdMediationProvider.ADMOB,
    val forceDebugEnabled: Boolean = false
)

data class AdDebugSettings(
    val debugEnabled: Boolean = false,
    val showToasts: Boolean = false,
    val keepEvents: Int = 100,
    val adIdOverrideMode: AdIdOverrideMode = AdIdOverrideMode.NORMAL,
    val customAdUnitModes: Map<String, AdUnitCustomMode> = emptyMap()
)

data class AdDebugEvent(
    val unit: AdDebugUnit,
    val action: AdDebugAction,
    val placement: String,
    val adUnitId: String? = null,
    val network: String? = null,
    val lineItem: String? = null,
    val precision: String? = null,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class AdDebugRevenueEvent(
    val unit: AdDebugUnit,
    val placement: String,
    val adUnitId: String? = null,
    val network: String? = null,
    val lineItem: String? = null,
    val valueMicros: Long,
    val currencyCode: String,
    val precision: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class AdDebugCustomEvent(
    val event: String,
    val status: String? = null,
    val message: String? = null,
    val values: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

data class AdDebugState(
    val placement: String,
    val unit: AdDebugUnit,
    val adUnitId: String?,
    val loadState: AdDebugLoadState,
    val showState: AdDebugShowState,
    val lastAction: AdDebugAction,
    val network: String?,
    val message: String?,
    val successCount: Int,
    val failedCount: Int,
    val showedCount: Int,
    val revenueMicros: Long,
    val currencyCode: String?,
    val updatedAtMs: Long
)
