package fxc.dev.ads_debug_kit

internal enum class ProviderOnlyPlacementMatch {
    NONE,
    CURRENT_PROVIDER,
    OTHER_PROVIDER
}

internal data class AdUnitIdResolutionInput(
    val configuredAdUnitId: String,
    val invalidAdUnitId: String,
    val debugAdUnitId: String,
    val overrideMode: AdIdOverrideMode,
    val customMode: AdUnitCustomMode,
    val requestRole: AdProviderRequestRole,
    val providerOnlyPlacementMatch: ProviderOnlyPlacementMatch,
    val isPriorityPlacement: Boolean,
    val overridesEnabled: Boolean,
    val debugEnabled: Boolean,
    val overridable: Boolean,
)

/** Pure resolver kept independent from Android so the provider/fallback matrix is unit-testable. */
internal object AdUnitIdOverrideResolver {
    fun resolve(input: AdUnitIdResolutionInput): String {
        if (!input.overridesEnabled || !input.debugEnabled || !input.overridable) {
            return input.configuredAdUnitId
        }

        val isProviderOnlyRequest =
            input.providerOnlyPlacementMatch != ProviderOnlyPlacementMatch.OTHER_PROVIDER &&
                (
                    input.requestRole == AdProviderRequestRole.PROVIDER_ONLY ||
                        input.providerOnlyPlacementMatch == ProviderOnlyPlacementMatch.CURRENT_PROVIDER
                    )

        return when (input.overrideMode) {
            AdIdOverrideMode.NORMAL -> input.configuredAdUnitId
            AdIdOverrideMode.FAIL_PRIMARY -> if (
                !isProviderOnlyRequest && input.isPriorityPlacement
            ) {
                input.invalidAdUnitId
            } else {
                input.configuredAdUnitId
            }
            AdIdOverrideMode.FAIL_ALL -> input.invalidAdUnitId
            AdIdOverrideMode.FORCE_ADMOB_ONLY -> if (isProviderOnlyRequest) {
                input.configuredAdUnitId
            } else {
                input.invalidAdUnitId
            }
            AdIdOverrideMode.CUSTOM -> when (input.customMode) {
                AdUnitCustomMode.RELEASE -> input.configuredAdUnitId
                AdUnitCustomMode.DEBUG -> input.debugAdUnitId
                AdUnitCustomMode.FALSE -> input.invalidAdUnitId
                AdUnitCustomMode.ADMOB_ONLY -> if (isProviderOnlyRequest) {
                    input.configuredAdUnitId
                } else {
                    input.invalidAdUnitId
                }
            }
        }
    }
}

internal fun providerOnlyProviderFor(
    placement: String,
    resourceSuffix: String,
): AdMediationProvider? {
    val normalizedPlacement = placement.lowercase()
    val normalizedSuffix = resourceSuffix.lowercase()
    return when {
        normalizedPlacement.endsWith("_admob_only$normalizedSuffix") -> AdMediationProvider.ADMOB
        normalizedPlacement.endsWith("_applovin_only$normalizedSuffix") ||
            normalizedPlacement.endsWith("_max_only$normalizedSuffix") -> {
            AdMediationProvider.APPLOVIN_MAX
        }
        else -> null
    }
}

/**
 * MAX Test Mode may fill unknown IDs that still look like valid 16-character hexadecimal IDs.
 * Keep IDs distinct per placement, but make them deliberately malformed so MAX returns
 * INVALID_AD_UNIT_ID instead of serving a test creative.
 */
internal fun maxInvalidAdUnitIdFor(placement: String): String {
    val placementHash = placement.hashCode().toUInt().toString(16).padStart(8, '0')
    return MAX_INVALID_AD_UNIT_PREFIX + placementHash
}

private const val MAX_INVALID_AD_UNIT_PREFIX = "invalid_"

internal fun normalizeAdDebugSettingsForCapabilities(
    settings: AdDebugSettings,
    allowAdUnitOverrides: Boolean,
    providerOnlyUnits: Set<AdDebugUnit>,
    unitByPlacement: Map<String, AdDebugUnit>,
): AdDebugSettings {
    if (!allowAdUnitOverrides) {
        return settings.copy(
            adIdOverrideMode = AdIdOverrideMode.NORMAL,
            customAdUnitModes = emptyMap(),
        )
    }
    val normalizedCustomModes = settings.customAdUnitModes.mapValues { (placement, mode) ->
        if (
            mode == AdUnitCustomMode.ADMOB_ONLY &&
            unitByPlacement[placement] !in providerOnlyUnits
        ) {
            AdUnitCustomMode.RELEASE
        } else {
            mode
        }
    }
    val normalizedMode = when {
        settings.adIdOverrideMode == AdIdOverrideMode.FORCE_ADMOB_ONLY &&
            providerOnlyUnits.isEmpty() -> AdIdOverrideMode.NORMAL
        settings.adIdOverrideMode == AdIdOverrideMode.CUSTOM &&
            normalizedCustomModes.isNotEmpty() &&
            normalizedCustomModes.values.all { it == AdUnitCustomMode.RELEASE } -> {
            AdIdOverrideMode.NORMAL
        }
        else -> settings.adIdOverrideMode
    }
    return settings.copy(
        adIdOverrideMode = normalizedMode,
        customAdUnitModes = if (normalizedMode == AdIdOverrideMode.NORMAL) {
            emptyMap()
        } else {
            normalizedCustomModes
        },
    )
}

internal fun customModesAfterSelection(
    currentModes: Map<String, AdUnitCustomMode>,
    placement: String,
    selectedMode: AdUnitCustomMode,
    selectedUnit: AdDebugUnit?,
    providerOnlyUnits: Map<String, AdDebugUnit>,
): Map<String, AdUnitCustomMode> {
    return currentModes.toMutableMap().apply {
        if (selectedMode == AdUnitCustomMode.ADMOB_ONLY) {
            providerOnlyUnits.forEach { (providerOnlyPlacement, providerOnlyUnit) ->
                if (selectedUnit == null || providerOnlyUnit == selectedUnit) {
                    this[providerOnlyPlacement] = AdUnitCustomMode.RELEASE
                }
            }
        }
        this[placement] = selectedMode
    }
}
