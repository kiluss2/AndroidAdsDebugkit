package fxc.dev.ads_debug_kit

import android.content.Context
import org.json.JSONObject

internal class AdsDebugPrefs(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ads_debug_kit_preferences",
        Context.MODE_PRIVATE
    )

    var settings: AdDebugSettings
        get() {
            val mode = runCatching {
                AdIdOverrideMode.valueOf(
                    preferences.getString(KEY_OVERRIDE_MODE, AdIdOverrideMode.NORMAL.name)
                        ?: AdIdOverrideMode.NORMAL.name
                )
            }.getOrDefault(AdIdOverrideMode.NORMAL)
            return AdDebugSettings(
                debugEnabled = preferences.getBoolean(KEY_DEBUG_ENABLED, false),
                showToasts = preferences.getBoolean(KEY_SHOW_TOASTS, false),
                keepEvents = preferences.getInt(KEY_KEEP_EVENTS, 100).coerceAtLeast(10),
                adIdOverrideMode = mode,
                customAdUnitModes = preferences.getString(KEY_CUSTOM_AD_UNIT_MODES, null)
                    .orEmpty()
                    .toCustomAdUnitModes()
            )
        }
        set(value) {
            preferences.edit()
                .putBoolean(KEY_DEBUG_ENABLED, value.debugEnabled)
                .putBoolean(KEY_SHOW_TOASTS, value.showToasts)
                .putInt(KEY_KEEP_EVENTS, value.keepEvents.coerceAtLeast(10))
                .putString(KEY_OVERRIDE_MODE, value.adIdOverrideMode.name)
                .putString(KEY_CUSTOM_AD_UNIT_MODES, value.customAdUnitModes.toJsonString())
                .apply()
        }

    private fun String.toCustomAdUnitModes(): Map<String, AdUnitCustomMode> {
        if (isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(this)
            buildMap {
                json.keys().forEach { key ->
                    val mode = runCatching {
                        AdUnitCustomMode.valueOf(json.optString(key))
                    }.getOrNull()
                    if (mode != null) put(key, mode)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun Map<String, AdUnitCustomMode>.toJsonString(): String {
        return JSONObject().also { json ->
            forEach { (placement, mode) ->
                json.put(placement, mode.name)
            }
        }.toString()
    }

    private companion object {
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_SHOW_TOASTS = "show_toasts"
        const val KEY_KEEP_EVENTS = "keep_events"
        const val KEY_OVERRIDE_MODE = "ad_id_override_mode"
        const val KEY_CUSTOM_AD_UNIT_MODES = "custom_ad_unit_modes"
    }
}
