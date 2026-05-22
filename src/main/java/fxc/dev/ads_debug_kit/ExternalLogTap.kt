package fxc.dev.ads_debug_kit

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

internal object ExternalLogTap {
    private const val MAX_BATCH_SIZE = 50

    private val isStarted = AtomicBoolean(false)
    private var logcatProcess: java.lang.Process? = null
    private var logcatReader: BufferedReader? = null
    private var readerThread: Thread? = null

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return

        readerThread = Thread({
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat",
                        "--pid=${Process.myPid()}",
                        "-v",
                        "time"
                    )
                )
                logcatProcess = process
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    logcatReader = reader
                    val batch = mutableListOf<String>()
                    while (isStarted.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (line.isAdsDebugFlowLine()) continue
                        val externalLine = line.toExternalTrackingLineOrNull() ?: continue
                        batch.add(externalLine)
                        if (batch.size >= MAX_BATCH_SIZE) {
                            AdsDebugKit.logDebugLines(batch.toList())
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) {
                        AdsDebugKit.logDebugLines(batch)
                    }
                }
            } catch (exception: Exception) {
                if (isStarted.get()) {
                    AdsDebugKit.logDebugLine(
                        "ExternalLogTap stopped unexpectedly: ${exception.javaClass.simpleName}"
                    )
                }
            } finally {
                isStarted.set(false)
                logcatReader = null
                logcatProcess?.closeAndDestroy()
                logcatProcess = null
            }
        }, "AdsDebugKit-LogcatTap").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!isStarted.compareAndSet(true, false)) return
        runCatching { logcatReader?.close() }
        logcatReader = null
        logcatProcess?.closeAndDestroy()
        logcatProcess = null
        readerThread?.interrupt()
        readerThread = null
    }

    private fun java.lang.Process.closeAndDestroy() {
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
        runCatching { outputStream.close() }
        destroy()
    }

    private fun String.isAdsDebugFlowLine(): Boolean {
        return ADS_DEBUG_FLOW_TAGS.any(::contains)
    }

    private fun String.toExternalTrackingLineOrNull(): String? {
        return toAdjustTrackingLineOrNull()
            ?: toFacebookTrackingLineOrNull()
    }

    private fun String.toAdjustTrackingLineOrNull(): String? {
        if (!isAdjustLine()) return null

        return if (isAdjustTrackedResponseLine()) {
            toAdjustRawLine()
        } else {
            null
        }
    }

    private fun String.isAdjustTrackedResponseLine(): Boolean {
        return contains("\"message\":\"Ad revenue tracked\"", ignoreCase = true) ||
                contains("\"purchase\"", ignoreCase = true) ||
                contains("\"message\":\"Event tracked", ignoreCase = true) ||
                contains("Response message: Event tracked", ignoreCase = true) ||
                isAdjustFailedResponseLine()
    }

    private fun String.isAdjustFailedResponseLine(): Boolean {
        return (contains("Response string:", ignoreCase = true) ||
                contains("Response message:", ignoreCase = true) ||
                contains("Event Failure", ignoreCase = true)) &&
                (contains("fail", ignoreCase = true) ||
                        contains("error", ignoreCase = true))
    }

    private fun String.toAdjustRawLine(): String {
        val rawMessage = when {
            contains("Response string:") -> "Response string:${substringAfter("Response string:")}"
            contains("Response message:") -> "Response message:${substringAfter("Response message:")}"
            else -> trim()
        }.trim()
        return "Adjust $rawMessage"
    }

    private fun String.toFacebookTrackingLineOrNull(): String? {
        if (contains(FB_FLUSH_COMPLETED_TOKEN)) {
            resetFacebookFlush()
            isCollectingFacebookFlush = true
            facebookFlushEvent = detectFacebookEvent()
            return null
        }

        if (!isCollectingFacebookFlush) {
            return null
        }

        facebookFlushLineCount++
        facebookFlushEvent = facebookFlushEvent ?: detectFacebookEvent()

        if (contains(FB_FLUSH_RESULT_TOKEN)) {
            facebookFlushStatus = if (contains("Success", ignoreCase = true)) {
                AdsDebugLogFormat.Status.SUCCESS
            } else {
                AdsDebugLogFormat.Status.FAILED
            }
            facebookFlushMessage = substringAfter(FB_FLUSH_RESULT_TOKEN, missingDelimiterValue = this)
                .trim()
                .sanitizeExternalMessage()
        }

        val externalLine = buildFacebookFlushLineOrNull()
        if (externalLine != null || facebookFlushLineCount > FB_MAX_FLUSH_LINES) {
            resetFacebookFlush()
        }
        return externalLine
    }

    private fun String.detectFacebookEvent(): String? {
        return when {
            contains(FB_PURCHASE_TOKEN, ignoreCase = true) -> "purchase"
            FB_AD_IMPRESSION_REGEX.containsMatchIn(this) -> "ad_revenue"
            else -> null
        }
    }

    private fun buildFacebookFlushLineOrNull(): String? {
        val event = facebookFlushEvent ?: return null
        val status = facebookFlushStatus ?: return null
        val message = facebookFlushMessage?.takeIf { it.isNotBlank() }
            ?.let { " message=$it" }
            .orEmpty()
        return "${AdsDebugLogFormat.EXTERNAL_MARKER} provider=meta event=$event status=$status$message"
    }

    private fun resetFacebookFlush() {
        isCollectingFacebookFlush = false
        facebookFlushEvent = null
        facebookFlushStatus = null
        facebookFlushMessage = null
        facebookFlushLineCount = 0
    }

    private fun String.isAdjustLine(): Boolean {
        return contains(" Adjust ") ||
                contains("/Adjust(") ||
                contains("Adjust:", ignoreCase = true)
    }

    private fun String.sanitizeExternalMessage(): String {
        return replace(Regex("\\s+"), "_")
            .replace("\"", "")
            .take(MAX_MESSAGE_LENGTH)
    }

    private val ADS_DEBUG_FLOW_TAGS = listOf(
        AdsDebugLogFormat.Tag.NATIVE,
        AdsDebugLogFormat.Tag.INTERSTITIAL,
        AdsDebugLogFormat.Tag.REWARDED,
        AdsDebugLogFormat.Tag.APP_OPEN,
        AdsDebugLogFormat.Tag.BANNER,
        AdsDebugLogFormat.Tag.REVENUE,
        AdsDebugLogFormat.Tag.EXTERNAL,
        AdsDebugLogFormat.Tag.INIT,
        AdsDebugLogFormat.Tag.LIFECYCLE
    )

    private const val FB_PURCHASE_TOKEN = "fb_mobile_purchase"
    private val FB_AD_IMPRESSION_REGEX = Regex("\"_eventName\"\\s*:\\s*\"AdImpression\"")
    private var isCollectingFacebookFlush = false
    private var facebookFlushEvent: String? = null
    private var facebookFlushStatus: String? = null
    private var facebookFlushMessage: String? = null
    private var facebookFlushLineCount = 0
    private const val FB_FLUSH_COMPLETED_TOKEN = "Flush completed"
    private const val FB_FLUSH_RESULT_TOKEN = "Result:"
    private const val FB_MAX_FLUSH_LINES = 120
    private const val MAX_MESSAGE_LENGTH = 180
}
