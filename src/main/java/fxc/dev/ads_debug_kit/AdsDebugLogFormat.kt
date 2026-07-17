package fxc.dev.ads_debug_kit

object AdsDebugLogFormat {
    const val MARKER = "ads_debug=1"
    const val EXTERNAL_MARKER = "external_debug=1"
    const val CUSTOM_MARKER = "custom_debug=1"

    object Tag {
        const val NATIVE = "NativeAdsDebugFlow"
        const val INTERSTITIAL = "InterAdsDebugFlow"
        const val REWARDED = "RewardAdsDebugFlow"
        const val APP_OPEN = "AppOpenAdsDebugFlow"
        const val BANNER = "BannerAdsDebugFlow"
        const val REVENUE = "AdsRevenueDebugFlow"
        const val EXTERNAL = "AdsExternalDebugFlow"
        const val CUSTOM = "AdsCustomDebugFlow"
        const val INIT = "AdsInitDebugFlow"
        const val LIFECYCLE = "AdsLifecycleDebugFlow"
    }

    object Key {
        const val EVENT = "event"
        const val PROVIDER = "provider"
        const val STATUS = "status"
        const val UNIT = "unit"
        const val PLACEMENT = "placement"
        const val AD_UNIT = "adUnit"
        const val NETWORK = "network"
        const val LINE_ITEM = "lineItem"
        const val MESSAGE = "message"
        const val REASON = "reason"
        const val CODE = "code"
        const val VALUE_MICROS = "valueMicros"
        const val CURRENCY = "currency"
        const val PRECISION = "precision"
    }

    object Event {
        const val LOAD_START = "load_start"
        const val LOAD_SUCCESS = "load_success"
        const val LOAD_FAIL = "load_fail"
        const val SHOW_START = "show_start"
        const val SHOW_SUCCESS = "show_success"
        const val SHOW_FAIL = "show_fail"
        const val SHOW_DISMISSED = "show_dismissed"
        const val CLICK = "click"
        const val IMPRESSION = "impression"
        const val POPULATE = "populate"
        const val EXPIRED = "expired"
        const val FALLBACK = "fallback"
        const val PAID = "paid"
    }

    object Status {
        const val SUBMITTED = "submitted"
        const val LOADING = "loading"
        const val SUCCESS = "success"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
    }

    object Unit {
        const val NATIVE = "NATIVE"
        const val INTERSTITIAL = "INTERSTITIAL"
        const val REWARDED = "REWARDED"
        const val APP_OPEN = "APP_OPEN"
        const val BANNER = "BANNER"
    }
}
