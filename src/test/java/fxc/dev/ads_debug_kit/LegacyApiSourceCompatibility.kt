package fxc.dev.ads_debug_kit

/** Compile-only fixture mirroring the named arguments and exhaustive enum usage in AdMob apps. */
@Suppress("unused")
private object LegacyApiSourceCompatibility {
    fun resolveWithNamedArguments(
        placement: String,
        primaryAdUnitId: String,
        admobOnlyAdUnitId: String,
    ): String {
        return AdsDebugKit.resolveAdUnitId(
            placement = placement,
            primaryAdUnitId = primaryAdUnitId,
            admobOnlyAdUnitId = admobOnlyAdUnitId,
            role = AdIdRequestRole.ADMOB_ONLY,
        )
    }

    fun exhaustivelyMatchLegacyMode(mode: AdIdOverrideMode): String {
        return when (mode) {
            AdIdOverrideMode.NORMAL -> "normal"
            AdIdOverrideMode.FAIL_PRIMARY -> "fail_primary"
            AdIdOverrideMode.FAIL_ALL -> "fail_all"
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> "force_fallback"
            AdIdOverrideMode.CUSTOM -> "custom"
        }
    }
}
