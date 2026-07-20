package fxc.dev.ads_debug_kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AdUnitIdOverrideResolverTest {
    @Test
    fun `force fallback invalidates primary and keeps provider only request configured`() {
        assertEquals(INVALID_ID, resolve(mode = AdIdOverrideMode.FORCE_FALLBACK))
        assertEquals(
            CONFIGURED_ID,
            resolve(
                mode = AdIdOverrideMode.FORCE_FALLBACK,
                role = AdProviderRequestRole.PROVIDER_ONLY,
            )
        )
    }

    @Test
    fun `force fallback infers provider only role from current provider resource`() {
        assertEquals(
            CONFIGURED_ID,
            resolve(
                mode = AdIdOverrideMode.FORCE_FALLBACK,
                placementMatch = ProviderOnlyPlacementMatch.CURRENT_PROVIDER,
            )
        )
    }

    @Test
    fun `force fallback rejects wrong provider resource even with provider only role`() {
        assertEquals(
            INVALID_ID,
            resolve(
                mode = AdIdOverrideMode.FORCE_FALLBACK,
                role = AdProviderRequestRole.PROVIDER_ONLY,
                placementMatch = ProviderOnlyPlacementMatch.OTHER_PROVIDER,
            )
        )
    }

    @Test
    fun `fail primary keeps provider only request configured but fail all invalidates it`() {
        assertEquals(
            CONFIGURED_ID,
            resolve(
                mode = AdIdOverrideMode.FAIL_PRIMARY,
                role = AdProviderRequestRole.PROVIDER_ONLY,
                priority = true,
            )
        )
        assertEquals(
            INVALID_ID,
            resolve(
                mode = AdIdOverrideMode.FAIL_ALL,
                role = AdProviderRequestRole.PROVIDER_ONLY,
            )
        )
    }

    @Test
    fun `custom fallback invalidates primary instead of substituting an id`() {
        assertEquals(
            INVALID_ID,
            resolve(
                mode = AdIdOverrideMode.CUSTOM,
                customMode = AdUnitCustomMode.FALLBACK,
            )
        )
        assertEquals(
            CONFIGURED_ID,
            resolve(
                mode = AdIdOverrideMode.CUSTOM,
                customMode = AdUnitCustomMode.FALLBACK,
                role = AdProviderRequestRole.PROVIDER_ONLY,
            )
        )
    }

    @Test
    fun `provider resource recognition is exact and provider aware`() {
        assertEquals(
            AdMediationProvider.ADMOB,
            providerOnlyProviderFor("ads_interstitial_admob_only_id", "_id")
        )
        assertEquals(
            AdMediationProvider.APPLOVIN_MAX,
            providerOnlyProviderFor("ads_interstitial_applovin_only_id", "_id")
        )
        assertEquals(
            AdMediationProvider.APPLOVIN_MAX,
            providerOnlyProviderFor("ads_interstitial_max_only_id", "_id")
        )
        assertEquals(
            null,
            providerOnlyProviderFor("ads_interstitial_admob_only_backup_id", "_id")
        )
    }

    @Test
    fun `provider neutral aliases preserve legacy enum entries`() {
        assertSame(AdIdOverrideMode.FORCE_ADMOB_ONLY, AdIdOverrideMode.FORCE_FALLBACK)
        assertSame(AdUnitCustomMode.ADMOB_ONLY, AdUnitCustomMode.FALLBACK)
    }

    @Test
    fun `max false ids are malformed and distinct per placement`() {
        val first = maxInvalidAdUnitIdFor("ads_native_intro_first_id")
        val second = maxInvalidAdUnitIdFor("ads_native_intro_second_id")

        assertEquals(16, first.length)
        assertEquals(true, first.startsWith("invalid_"))
        assertEquals(false, first.matches(Regex("[0-9a-f]{16}")))
        assertEquals(false, first == second)
    }

    @Test
    fun `failure modes return force fail behavior instead of relying on invalid ids`() {
        assertEquals(
            AdDebugRequestBehavior.FORCE_FAIL,
            resolveRequest(mode = AdIdOverrideMode.FAIL_ALL).behavior,
        )
        assertEquals(
            AdDebugRequestBehavior.FORCE_FAIL,
            resolveRequest(
                mode = AdIdOverrideMode.FAIL_PRIMARY,
                priority = true,
            ).behavior,
        )
        assertEquals(
            AdDebugRequestBehavior.FORCE_FAIL,
            resolveRequest(
                mode = AdIdOverrideMode.CUSTOM,
                customMode = AdUnitCustomMode.FALSE,
            ).behavior,
        )
    }

    @Test
    fun `release debug and provider-only fallback requests remain allowed`() {
        assertEquals(
            AdDebugRequestResolution(CONFIGURED_ID),
            resolveRequest(mode = AdIdOverrideMode.NORMAL),
        )
        assertEquals(
            AdDebugRequestResolution(DEBUG_ID),
            resolveRequest(
                mode = AdIdOverrideMode.CUSTOM,
                customMode = AdUnitCustomMode.DEBUG,
            ),
        )
        assertEquals(
            AdDebugRequestResolution(CONFIGURED_ID),
            resolveRequest(
                mode = AdIdOverrideMode.FORCE_FALLBACK,
                role = AdProviderRequestRole.PROVIDER_ONLY,
            ),
        )
    }

    @Test
    fun `missing provider only capability removes unsafe fallback modes`() {
        assertEquals(
            AdDebugSettings(),
            normalizeAdDebugSettingsForCapabilities(
                settings = AdDebugSettings(
                    adIdOverrideMode = AdIdOverrideMode.FORCE_FALLBACK,
                    customAdUnitModes = mapOf("primary" to AdUnitCustomMode.FALLBACK),
                ),
                allowAdUnitOverrides = true,
                providerOnlyUnits = emptySet(),
                unitByPlacement = mapOf("primary" to AdDebugUnit.INTERSTITIAL),
            )
        )

        assertEquals(
            AdDebugSettings(
                adIdOverrideMode = AdIdOverrideMode.CUSTOM,
                customAdUnitModes = mapOf(
                    "primary" to AdUnitCustomMode.RELEASE,
                    "other" to AdUnitCustomMode.FALSE,
                ),
            ),
            normalizeAdDebugSettingsForCapabilities(
                settings = AdDebugSettings(
                    adIdOverrideMode = AdIdOverrideMode.CUSTOM,
                    customAdUnitModes = mapOf(
                        "primary" to AdUnitCustomMode.FALLBACK,
                        "other" to AdUnitCustomMode.FALSE,
                    ),
                ),
                allowAdUnitOverrides = true,
                providerOnlyUnits = emptySet(),
                unitByPlacement = mapOf(
                    "primary" to AdDebugUnit.INTERSTITIAL,
                    "other" to AdDebugUnit.NATIVE,
                ),
            )
        )
    }

    @Test
    fun `persisted custom fallback is normalized per format`() {
        assertEquals(
            AdDebugSettings(
                adIdOverrideMode = AdIdOverrideMode.CUSTOM,
                customAdUnitModes = mapOf(
                    "interstitial_primary" to AdUnitCustomMode.FALLBACK,
                    "native_primary" to AdUnitCustomMode.RELEASE,
                ),
            ),
            normalizeAdDebugSettingsForCapabilities(
                settings = AdDebugSettings(
                    adIdOverrideMode = AdIdOverrideMode.CUSTOM,
                    customAdUnitModes = mapOf(
                        "interstitial_primary" to AdUnitCustomMode.FALLBACK,
                        "native_primary" to AdUnitCustomMode.FALLBACK,
                    ),
                ),
                allowAdUnitOverrides = true,
                providerOnlyUnits = setOf(AdDebugUnit.INTERSTITIAL),
                unitByPlacement = mapOf(
                    "interstitial_primary" to AdDebugUnit.INTERSTITIAL,
                    "native_primary" to AdDebugUnit.NATIVE,
                ),
            )
        )
    }

    @Test
    fun `selecting custom fallback after fail all releases same format provider only id`() {
        assertEquals(
            mapOf(
                "primary_interstitial" to AdUnitCustomMode.FALLBACK,
                "primary_native" to AdUnitCustomMode.FALSE,
                "only_interstitial" to AdUnitCustomMode.RELEASE,
                "only_native" to AdUnitCustomMode.FALSE,
            ),
            customModesAfterSelection(
                currentModes = mapOf(
                    "primary_interstitial" to AdUnitCustomMode.FALSE,
                    "primary_native" to AdUnitCustomMode.FALSE,
                    "only_interstitial" to AdUnitCustomMode.FALSE,
                    "only_native" to AdUnitCustomMode.FALSE,
                ),
                placement = "primary_interstitial",
                selectedMode = AdUnitCustomMode.FALLBACK,
                selectedUnit = AdDebugUnit.INTERSTITIAL,
                providerOnlyUnits = mapOf(
                    "only_interstitial" to AdDebugUnit.INTERSTITIAL,
                    "only_native" to AdDebugUnit.NATIVE,
                ),
            )
        )
    }

    private fun resolve(
        mode: AdIdOverrideMode,
        customMode: AdUnitCustomMode = AdUnitCustomMode.RELEASE,
        role: AdProviderRequestRole = AdProviderRequestRole.PRIMARY,
        placementMatch: ProviderOnlyPlacementMatch = ProviderOnlyPlacementMatch.NONE,
        priority: Boolean = false,
    ): String {
        return AdUnitIdOverrideResolver.resolve(
            AdUnitIdResolutionInput(
                configuredAdUnitId = CONFIGURED_ID,
                invalidAdUnitId = INVALID_ID,
                debugAdUnitId = DEBUG_ID,
                overrideMode = mode,
                customMode = customMode,
                requestRole = role,
                providerOnlyPlacementMatch = placementMatch,
                isPriorityPlacement = priority,
                overridesEnabled = true,
                debugEnabled = true,
                overridable = true,
            )
        )
    }

    private fun resolveRequest(
        mode: AdIdOverrideMode,
        customMode: AdUnitCustomMode = AdUnitCustomMode.RELEASE,
        role: AdProviderRequestRole = AdProviderRequestRole.PRIMARY,
        placementMatch: ProviderOnlyPlacementMatch = ProviderOnlyPlacementMatch.NONE,
        priority: Boolean = false,
    ): AdDebugRequestResolution {
        return AdUnitIdOverrideResolver.resolveRequest(
            AdUnitIdResolutionInput(
                configuredAdUnitId = CONFIGURED_ID,
                invalidAdUnitId = INVALID_ID,
                debugAdUnitId = DEBUG_ID,
                overrideMode = mode,
                customMode = customMode,
                requestRole = role,
                providerOnlyPlacementMatch = placementMatch,
                isPriorityPlacement = priority,
                overridesEnabled = true,
                debugEnabled = true,
                overridable = true,
            )
        )
    }

    private companion object {
        const val CONFIGURED_ID = "configured"
        const val INVALID_ID = "invalid"
        const val DEBUG_ID = "debug"
    }
}
