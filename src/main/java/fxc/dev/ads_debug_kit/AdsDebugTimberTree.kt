package fxc.dev.ads_debug_kit

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

internal object AdsDebugTimberTree {
    private val isPlanted = AtomicBoolean(false)
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val config = AdsDebugKit.currentConfig()
            if (!config.captureTimberLogs || !tag.isAdsDebugTag(config)) return

            if (tag == AdsDebugLogFormat.Tag.EXTERNAL) {
                AdsDebugKit.logDebugLine(message)
            }
            parseStructuredEvent(tag, message)?.let(AdsDebugKit::log)
            parseStructuredRevenue(tag, message)?.let(AdsDebugKit::logRevenue)
            parseStructuredCustomEvent(tag, message)?.let(AdsDebugKit::logCustomEvent)

            if (config.mirrorTimberLogsToLogcat) {
                Log.println(priority, tag ?: DEFAULT_TAG, message)
                t?.let { Log.println(priority, tag ?: DEFAULT_TAG, Log.getStackTraceString(it)) }
            }
        }
    }

    fun start() {
        if (isPlanted.compareAndSet(false, true)) {
            Timber.plant(tree)
        }
    }

    fun stop() {
        if (isPlanted.compareAndSet(true, false)) {
            runCatching { Timber.uproot(tree) }
        }
    }

    private fun String?.isAdsDebugTag(config: AdDebugConfig): Boolean {
        if (this.isNullOrBlank()) return false
        return this in config.timberLogTags || endsWith(ADS_DEBUG_FLOW_SUFFIX)
    }

    private fun parseStructuredEvent(tag: String?, message: String): AdDebugEvent? {
        val values = message.parseKeyValues()
        if (values[FORMAT_KEY] != FORMAT_VERSION) return null
        val event = values[AdsDebugLogFormat.Key.EVENT] ?: return null
        if (event == AdsDebugLogFormat.Event.PAID) return null
        val unit = values["unit"]?.toDebugUnit() ?: tag.toDebugUnit()
        val action = event.toDebugAction() ?: return null
        val placement = values["placement"] ?: values["adUnit"] ?: tag ?: DEFAULT_TAG
        val eventMessage = listOfNotNull(
            values[AdsDebugLogFormat.Key.CODE]?.let { "code=$it" },
            values[AdsDebugLogFormat.Key.MESSAGE] ?: values[AdsDebugLogFormat.Key.REASON],
        ).joinToString(separator = " ").ifBlank { null }
        return AdDebugEvent(
            unit = unit,
            action = action,
            placement = placement,
            adUnitId = values["adUnit"],
            network = values["network"],
            lineItem = values["lineItem"],
            message = eventMessage,
        )
    }

    private fun parseStructuredRevenue(tag: String?, message: String): AdDebugRevenueEvent? {
        val values = message.parseKeyValues()
        if (values[FORMAT_KEY] != FORMAT_VERSION) return null
        if (values[AdsDebugLogFormat.Key.EVENT] != AdsDebugLogFormat.Event.PAID) return null
        val placement = values["placement"] ?: values["adUnit"] ?: return null
        return AdDebugRevenueEvent(
            unit = values["unit"]?.toDebugUnit() ?: tag.toDebugUnit(),
            placement = placement,
            adUnitId = values["adUnit"],
            network = values["network"],
            lineItem = values["lineItem"],
            valueMicros = values["valueMicros"]?.toLongOrNull() ?: return null,
            currencyCode = values["currency"] ?: "USD",
            precision = values["precision"]
        )
    }

    private fun parseStructuredCustomEvent(tag: String?, message: String): AdDebugCustomEvent? {
        if (tag != AdsDebugLogFormat.Tag.CUSTOM) return null
        val values = message.parseKeyValues()
        if (values[CUSTOM_FORMAT_KEY] != FORMAT_VERSION) return null
        val event = values[AdsDebugLogFormat.Key.EVENT] ?: return null
        return AdDebugCustomEvent(
            event = event,
            status = values[AdsDebugLogFormat.Key.STATUS],
            message = values[AdsDebugLogFormat.Key.MESSAGE]
                ?: values[AdsDebugLogFormat.Key.REASON]
                ?: values[AdsDebugLogFormat.Key.CODE],
            values = values
                .filterKeys { key -> key != CUSTOM_FORMAT_KEY }
                .toSortedMap()
        )
    }

    private fun String.parseKeyValues(): Map<String, String> {
        return KEY_VALUE_REGEX.findAll(this)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    private fun String.toDebugAction(): AdDebugAction? {
        return when (this) {
            AdsDebugLogFormat.Event.LOAD_START -> AdDebugAction.LOAD_START
            AdsDebugLogFormat.Event.LOAD_SUCCESS -> AdDebugAction.LOAD_SUCCESS
            "load_failed",
            AdsDebugLogFormat.Event.LOAD_FAIL,
            "load_skipped" -> AdDebugAction.LOAD_FAIL
            AdsDebugLogFormat.Event.SHOW_START,
            "show_requested" -> AdDebugAction.SHOW_START
            AdsDebugLogFormat.Event.SHOW_SUCCESS -> AdDebugAction.SHOW_SUCCESS
            AdsDebugLogFormat.Event.SHOW_FAIL -> AdDebugAction.SHOW_FAIL
            AdsDebugLogFormat.Event.SHOW_DISMISSED -> AdDebugAction.SHOW_DISMISSED
            AdsDebugLogFormat.Event.CLICK -> AdDebugAction.CLICK
            AdsDebugLogFormat.Event.IMPRESSION -> AdDebugAction.IMPRESSION
            AdsDebugLogFormat.Event.POPULATE,
            "populate_native" -> AdDebugAction.POPULATE
            AdsDebugLogFormat.Event.EXPIRED -> AdDebugAction.EXPIRED
            AdsDebugLogFormat.Event.FALLBACK,
            "retry_admob_only" -> AdDebugAction.FALLBACK
            else -> null
        }
    }

    private fun String?.toDebugUnit(): AdDebugUnit {
        val value = this.orEmpty()
        return when {
            value == "NATIVE" || value.contains("Native", ignoreCase = true) -> AdDebugUnit.NATIVE
            value == "INTERSTITIAL" || value.contains("Inter", ignoreCase = true) -> AdDebugUnit.INTERSTITIAL
            value == "REWARDED" || value.contains("Reward", ignoreCase = true) -> AdDebugUnit.REWARDED
            value == "APP_OPEN" || value.contains("AppOpen", ignoreCase = true) -> AdDebugUnit.APP_OPEN
            value == "BANNER" || value.contains("Banner", ignoreCase = true) -> AdDebugUnit.BANNER
            else -> AdDebugUnit.OTHER
        }
    }

    private fun Int.toPriorityName(): String {
        return when (this) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> toString()
        }
    }

    private const val DEFAULT_TAG = "AdsDebugKit"
    private const val ADS_DEBUG_FLOW_SUFFIX = "DebugFlow"
    private const val FORMAT_KEY = "ads_debug"
    private const val CUSTOM_FORMAT_KEY = "custom_debug"
    private const val FORMAT_VERSION = "1"
    private val KEY_VALUE_REGEX = Regex("""([A-Za-z][A-Za-z0-9_]*)=(.*?)(?=\s+[A-Za-z][A-Za-z0-9_]*=|$)""")
}
