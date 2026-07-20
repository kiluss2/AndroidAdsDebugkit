# AndroidAdsDebugKit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kiluss2/android-ads-debug-kit.svg)](https://central.sonatype.com/artifact/io.github.kiluss2/android-ads-debug-kit)
[![GitHub Release](https://img.shields.io/github/v/release/kiluss2/AndroidAdsDebugkit)](https://github.com/kiluss2/AndroidAdsDebugkit/releases)

AndroidAdsDebugKit is an in-app debug panel for inspecting ads, ad revenue, external tracking logs, and runtime ad unit overrides in debug or release builds.

It is designed for production QA flows where testers need to enable a hidden debug panel from inside the app, inspect real ad states, force ad-load failures, test tier fallback, and verify external SDK tracking without rebuilding the app.

## Features

- Hidden unlock gesture for release builds.
- Floating near-fullscreen debug sheet that survives Activity navigation.
- Ad state dashboard for AdMob or AppLovin MAX ads: load, show/impression, revenue.
- External tracking log tab for Adjust, Facebook, TikTok, AppsFlyer, and similar SDK logs.
- Custom event tab for app-defined QA/debug events.
- Provider-aware AdMob and AppLovin MAX runtime modes.
- Optional runtime ad unit override modes: normal, fail primary, fail all, force fallback, custom per placement.
- Provider-neutral custom tool actions that receive the current `Activity`.
- Automatic ad unit discovery from `R.string` resources matching `ads_*_id`.
- Structured Timber parser for ads and external logs.
- R8 consumer rules for resource-name discovery.

## Installation

### Maven Central

Release coordinate:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.kiluss2:android-ads-debug-kit:0.2.4")
}
```

Apps that emit structured DebugKit logs from app code should also depend on Timber, or route the same messages through an existing Timber-compatible logging layer.

### Local Development

For local library development, publish the current checkout to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then put `mavenLocal()` before `mavenCentral()` in the consumer app while testing local changes:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.kiluss2:android-ads-debug-kit:0.2.4")
}
```

Remove `mavenLocal()` again before committing app changes that should consume the public Maven Central artifact.

## Quick Start

Initialize once from your `Application`:

```kotlin
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AdsDebugKit.initialize(
            context = this,
            config = AdDebugConfig()
        )
    }
}
```

Attach the hidden unlock gesture to a view such as the app icon on the splash screen:

```kotlin
DebugComboGestureHelper().setup(appIconView) {
    AdsDebugKit.show()
}
```

Default unlock gesture:

1. Swipe down.
2. Double tap.
3. Swipe up.

After debug mode is enabled, shake the device to toggle the debug panel.

## Release Safety

`AdsDebugKit` is safe to include in release builds when debug mode is disabled.

When `debugEnabled = false`:

- Ad unit override is disabled.
- `resolveAdUnitId(...)` returns the original configured IDs.
- Timber capture is stopped.
- Logcat tap is stopped.
- Shake detector is stopped.
- Overlay is hidden.

This means app ads use the normal `configs.xml` / production configuration unless a tester explicitly enables the hidden debug mode. Provider and tracking SDK initialization should read the persisted setting at app startup; restart after changing it when the SDK cannot safely change mode in-process.

## Ad Unit Discovery

By default, the kit auto-discovers non-empty string resources matching:

```text
ads_*_id
```

Example:

```xml
<string name="ads_interstitial_open_id" translatable="false">ca-app-pub-xxx/yyy</string>
<string name="ads_native_language_id" translatable="false">ca-app-pub-xxx/yyy</string>
<string name="ads_rewarded_id" translatable="false">ca-app-pub-xxx/yyy</string>
```

The resource name is treated as the placement:

```text
placement = ads_interstitial_open_id
adUnitId = ca-app-pub-xxx/yyy
```

The placement is important because multiple placements can share the same ad unit ID, but DebugKit still needs to track and override each placement independently.

For custom apps, pass manual units:

```kotlin
AdsDebugKit.initialize(
    context = this,
    config = AdDebugConfig(
        allAdUnits = {
            listOf(
                AdDebugAdUnit(
                    name = "ads_interstitial_open_id",
                    adUnitId = getString(R.string.ads_interstitial_open_id),
                    unit = AdDebugUnit.INTERSTITIAL
                )
            )
        }
    )
)
```

If startup cost matters and your direct `R.string` class is enough, disable Dex fallback scanning:

```kotlin
AdDebugConfig(
    enableDexResourceScan = false
)
```

## Read-only Ad Units

Apps using a mediation SDK without reusable sample IDs can keep the Ad Units tab read-only:

```kotlin
AdDebugConfig(
    allowAdUnitOverrides = false
)
```

In this mode each resolver call returns the configured ID for that request, override controls are hidden, and existing AdMob consumers keep the original behavior because the option defaults to `true`.

## AppLovin MAX

Select MAX explicitly and keep overrides enabled:

```kotlin
AdDebugConfig(
    mediationProvider = AdMediationProvider.APPLOVIN_MAX,
    allowAdUnitOverrides = true
)
```

MAX has no reusable sample ad-unit IDs. In `Test` mode DebugKit therefore returns the configured app ID unchanged; the host app must initialize MAX Test Mode. `False` creates a stable invalid 16-character ID per placement. No production MAX IDs are embedded in DebugKit.

DebugKit recognizes provider-only resources by exact suffix:

- AdMob: `*_admob_only_id`
- AppLovin MAX: `*_applovin_only_id` (`*_max_only_id` is accepted as a legacy/short alias)

`FORCE_FALLBACK` is available only when a provider-only resource for the selected provider is discovered through resources or `allAdUnits`. It makes every primary request use an invalid ID and keeps only provider-only requests configured. DebugKit never maps 2F/MF IDs to base/AP IDs, never starts a fallback request, and never substitutes a provider-only ID into a primary request. The host app owns its actual primary-failure → fallback callback flow. Apps without provider-only resources, such as an app that has not implemented fallback yet, do not see this mode in the cycle or per-unit controls. Per-placement `Fallback` is shown only for ad formats that have a matching provider-only format registered.

MAX ad objects bind their ad unit during construction. Initialize DebugKit before ads/tracking and restart the app after changing debug or override settings. Set `forceDebugEnabled = true` only for a QA build that must start with DebugKit and SDK test configuration enabled. The forced value is not written into the tester's persisted toggle, so changing the build config back to `false` does not accidentally keep QA mode enabled.

## Custom Tools

Register provider-specific tools without adding that provider SDK to DebugKit:

```kotlin
AdDebugConfig(
    toolActions = listOf(
        AdDebugToolAction(
            id = "mediation_debugger",
            title = "Open Mediation Debugger",
            action = { activity -> openMediationDebugger(activity) }
        )
    )
)
```

The panel is closed before an action runs. Exceptions are caught and surfaced as a safe in-app error instead of crashing the host app.

## Runtime Ad Unit Override

### Provider-neutral API

Pass the configured ID for the request currently being made. Primary and provider-only requests are separate calls because the host app owns the fallback flow. MAX integrations should use the structured resolver and skip the SDK request when `behavior == FORCE_FAIL`:

```kotlin
val primaryRequest = AdsDebugKit.resolveProviderAdRequest(
    placement = "ads_interstitial_id",
    configuredAdUnitId = getString(R.string.ads_interstitial_id),
    role = AdProviderRequestRole.PRIMARY
)

if (primaryRequest.behavior == AdDebugRequestBehavior.FORCE_FAIL) {
    // Run the host's normal load-failure callback without constructing an SDK ad object.
} else {
    loadMaxAd(primaryRequest.adUnitId)
}

// Call this only from the host app's real primary-failure fallback path.
val providerOnlyRequest = AdsDebugKit.resolveProviderAdRequest(
    placement = "ads_interstitial_applovin_only_id",
    configuredAdUnitId = getString(R.string.ads_interstitial_applovin_only_id),
    role = AdProviderRequestRole.PROVIDER_ONLY
)
```

Register provider-only resources in the normal `ads_*_id` discovery set (or `allAdUnits`) so DebugKit can expose `FORCE_FALLBACK` safely.

### Legacy AdMob API

The existing API and named arguments remain available for AdMob consumers:

```kotlin
val requestAdUnitId = AdsDebugKit.resolveAdUnitId(
    placement = "ads_interstitial_open_id",
    primaryAdUnitId = getString(R.string.ads_interstitial_open_id),
    admobOnlyAdUnitId = getString(R.string.ads_interstitial_admob_only_id),
    role = AdIdRequestRole.PRIMARY
)
```

For an AdMob-only fallback request:

```kotlin
val fallbackAdUnitId = AdsDebugKit.resolveAdUnitId(
    placement = "ads_interstitial_open_id",
    primaryAdUnitId = getString(R.string.ads_interstitial_open_id),
    admobOnlyAdUnitId = getString(R.string.ads_interstitial_admob_only_id),
    role = AdIdRequestRole.ADMOB_ONLY
)
```

Recommended architecture: keep this call behind a small bridge inside your ads module. That keeps ads code independent from UI/debug panel details.

```kotlin
internal object AdsDebugBridge {
    fun resolveAdUnitId(
        placement: String,
        primaryAdUnitId: String,
        admobOnlyAdUnitId: String? = null,
        role: AdIdRequestRole = AdIdRequestRole.PRIMARY
    ): String {
        if (!AdsDebugKit.isInitialized()) return primaryAdUnitId
        return AdsDebugKit.resolveAdUnitId(
            placement = placement,
            primaryAdUnitId = primaryAdUnitId,
            admobOnlyAdUnitId = admobOnlyAdUnitId,
            role = role
        )
    }
}
```

For compatibility with existing consumers, the string-only resolvers remain available. New provider-neutral integrations should use `resolveProviderAdRequest(...)`; its `Fallback` control returns `FORCE_FAIL` for the primary so the host app's real failure callback performs the provider-only request. This avoids relying on fabricated IDs, which some SDK test modes may unexpectedly fill.

### Override Modes

- `NORMAL`: use configured app IDs.
- `FAIL_PRIMARY`: priority placements such as `_2F_id` and `_MF_id` return `FORCE_FAIL`; normal and provider-only requests stay configured.
- `FAIL_ALL`: all overridable requests return `FORCE_FAIL`.
- `FORCE_FALLBACK`: every primary request returns `FORCE_FAIL`; only the current provider's provider-only request/resource stays configured. This mode is hidden when no provider-only resource is registered.
- `CUSTOM`: each placement can be set to `Release`, `Debug`/`Test`, `False`, or `Fallback` when provider-only fallback is available. `False` and primary `Fallback` return `FORCE_FAIL`.

For compatibility, the persisted enum entry remains `FORCE_ADMOB_ONLY` and `AdUnitCustomMode.ADMOB_ONLY`; provider-neutral source aliases `FORCE_FALLBACK` and `FALLBACK` point to those same entries. They are companion aliases rather than new enum entries, so persisted `.name`, `entries`, and `valueOf(...)` continue to use the legacy names.

`ads_app_id` and app IDs in the `ca-app-pub-xxx~yyy` format are treated as read-only and are not overridden.

## Structured Ads Logging

DebugKit updates Ad States by parsing structured Timber logs while debug mode is enabled.

Use `AdsDebugLogFormat` constants instead of hardcoded strings where possible:

```kotlin
// DO NOT MODIFY ads_debug Timber format unless maintaining AndroidAdsDebugKit parser.
Timber.tag(AdsDebugLogFormat.Tag.NATIVE).d(
    "${AdsDebugLogFormat.MARKER} " +
        "event=${AdsDebugLogFormat.Event.LOAD_SUCCESS} " +
        "unit=${AdsDebugLogFormat.Unit.NATIVE} " +
        "placement=ads_native_language_id " +
        "adUnit=ca-app-pub-xxx/yyy " +
        "network=AdMob"
)
```

Required fields:

- `ads_debug=1`
- `event`
- `unit`
- `placement`

Useful optional fields:

- `adUnit`
- `network`
- `lineItem`
- `message`
- `reason`
- `code`
- `valueMicros`
- `currency`
- `precision`

Supported ad events:

- `load_start`
- `load_success`
- `load_fail`
- `show_start`
- `show_success`
- `show_fail`
- `show_dismissed`
- `click`
- `impression`
- `populate`
- `expired`
- `fallback`
- `paid`

Revenue should be emitted from GMA `OnPaidEventListener`:

```kotlin
Timber.tag(AdsDebugLogFormat.Tag.REVENUE).d(
    "${AdsDebugLogFormat.MARKER} " +
        "event=${AdsDebugLogFormat.Event.PAID} " +
        "unit=${AdsDebugLogFormat.Unit.INTERSTITIAL} " +
        "placement=ads_interstitial_open_id " +
        "adUnit=ca-app-pub-xxx/yyy " +
        "valueMicros=12345 " +
        "currency=USD " +
        "network=AdMob"
)
```

In-app promo or fallback UI should not emit `ads_debug=1`. It is app monetization UI, not a real ad SDK state.

## External Tracking Logs

External tracking logs use:

```text
external_debug=1 provider=<provider> event=<event> status=<status> message=<optional>
```

Example:

```kotlin
Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL).d(
    "${AdsDebugLogFormat.EXTERNAL_MARKER} " +
        "provider=appsflyer event=purchase status=success message=ok"
)
```

Supported status values:

- `submitted`
- `loading`
- `success`
- `failed`
- `skipped`

DebugKit also taps filtered logcat lines for selected SDK outputs such as Adjust response strings and Facebook flush results while debug mode is enabled.

For reliable provider status, emit `external_debug=1` directly from the app tracking layer at the point each SDK is initialized or receives a callback. Do not rely only on SDK logcat output because some SDKs, especially Facebook, do not expose a stable init-success log line.

Recommended init logging:

```kotlin
// DO NOT MODIFY external_debug Timber format unless maintaining AndroidAdsDebugKit parser.
Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL).d(
    "${AdsDebugLogFormat.EXTERNAL_MARKER} " +
        "provider=facebook event=init status=${AdsDebugLogFormat.Status.SUCCESS} " +
        "message=fully_initialized=true"
)
```

Recommended provider names:

- `adjust`
- `facebook`
- `tiktok`
- `appsflyer`
- `in_house`

Recommended event names:

- `init`
- `purchase`
- `ad_revenue`
- `custom`

For SDKs with real callbacks, log `success` or `failed` from the callback. For SDKs with no reliable init callback, log the best observable local state, such as `FacebookSdk.isInitialized()` / `FacebookSdk.isFullyInitialized()`, then rely on flush or response logs for server-side success or failure.

### Provider SDK Hooks

To match the Coin app `Externals` tab, each provider should emit two signals:

- Structured app-side status: `external_debug=1 provider=<provider> event=<event> status=<status>`.
- Raw SDK callback/response lines through `Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL)` when the SDK exposes useful delivery state.

Provider-specific hooks:

- Facebook: enable app-event debug logs and register `AppEventsLogger.ACTION_APP_EVENTS_FLUSHED` while DebugKit is enabled.

```kotlin
FacebookSdk.setIsDebugEnabled(true)
FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
LocalBroadcastManager.getInstance(application).registerReceiver(
    facebookFlushReceiver,
    IntentFilter(AppEventsLogger.ACTION_APP_EVENTS_FLUSHED)
)

// In receiver:
Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL)
    .d("Facebook Flush completed result=$result numEvents=$numEvents events=$events")
```

- Adjust: install a custom `ILogger` before `Adjust.initSdk(config)` and forward only meaningful response lines.

```kotlin
AdjustFactory.setLogger(AdjustExternalDebugLogger())
config.setLogLevel(LogLevel.VERBOSE)

// In ILogger:
Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL).d("Adjust $line")
```

Forward Adjust lines containing event/ad-revenue/purchase success or failure, such as `Event tracked`, `Ad revenue tracked`, `purchase`, `Response string`, `Response message`, or `Event Failure`.

- TikTok: enable debug mode, log init callbacks, and register network listeners.

```kotlin
ttConfig.openDebugMode().setLogLevel(TikTokBusinessSdk.LogLevel.DEBUG)
TikTokBusinessSdk.initializeSdk(ttConfig, initCallback)
TikTokBusinessSdk.setUpSdkListeners(null, null, networkListener, null)

Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL).d("TikTok Init success")
Timber.tag(AdsDebugLogFormat.Tag.EXTERNAL)
    .d("TikTok Network toSend=$toSend success=$success failed=$failed total=$total")
```

Keep these hooks in the app's tracking layer, not in UI code. Guard raw SDK hooks with `BuildConfig.DEBUG || AdsDebugKit.isDebugEnabled()` if they are too noisy for normal release usage.

## Custom Debug Events

Use custom events for app-specific QA signals that should not be mixed into ad states or external SDK tracking.

Format:

```text
custom_debug=1 event=<event> status=<optional> message=<optional>
```

Example:

```kotlin
// DO NOT MODIFY custom_debug Timber format unless maintaining AndroidAdsDebugKit parser.
Timber.tag(AdsDebugLogFormat.Tag.CUSTOM).d(
    "${AdsDebugLogFormat.CUSTOM_MARKER} " +
        "event=paywall_opened " +
        "status=${AdsDebugLogFormat.Status.SUCCESS} " +
        "message=onboarding"
)
```

The `Custom` tab shows only entries emitted with `custom_debug=1` and `AdsDebugLogFormat.Tag.CUSTOM`.

## Configuration

```kotlin
AdsDebugKit.initialize(
    context = this,
    config = AdDebugConfig(
        autoDiscoverAdUnits = true,
        enableDexResourceScan = true,
        resourceClassNames = emptyList(),
        adUnitResourcePrefix = "ads_",
        adUnitResourceSuffix = "_id",
        captureTimberLogs = true,
        mirrorTimberLogsToLogcat = true,
        invalidAdUnitId = "ca-app-pub-3940256099942544/0000000000",
        backgroundDrawableResId = R.drawable.ads_debug_background,
        allowAdUnitOverrides = true,
        toolActions = emptyList(),
        mediationProvider = AdMediationProvider.ADMOB,
        forceDebugEnabled = false
    )
)
```

Set `backgroundDrawableResId = 0` to disable the built-in panel background image.

## R8 / Proguard

The library ships with a consumer rule:

```proguard
-keep class **.R$string {
    public static int *;
}
```

This keeps string resource fields available for automatic ad unit discovery in minified release builds.

## Public API Surface

Main entry points:

- `AdsDebugKit.initialize(...)`
- `AdsDebugKit.show()`
- `AdsDebugKit.hide()`
- `AdsDebugKit.toggle()`
- `AdsDebugKit.setDebugEnabled(...)`
- `AdsDebugKit.resolveAdUnitId(...)`
- `AdsDebugKit.resolveProviderAdUnitId(...)`
- `AdsDebugKit.resolveProviderAdRequest(...)`
- `AdsDebugKit.adRequestSettingsRevision()`
- `DebugComboGestureHelper`
- `AdDebugConfig`
- `AdMediationProvider`
- `AdDebugAdUnit`
- `AdDebugToolAction`
- `AdDebugUnit`
- `AdIdRequestRole`
- `AdProviderRequestRole`
- `AdDebugRequestBehavior`
- `AdDebugRequestResolution`
- `AdsDebugLogFormat`

Implementation details such as the window manager, panel view, logcat tap, and Timber parser are internal.

## Verification

Library checks:

```bash
./gradlew clean testDebugUnitTest compileReleaseKotlin lintRelease checkSigningConfiguration
```

Consumer app checks:

```bash
./gradlew :app:compileDebugKotlin :app:minifyReleaseWithR8
```

## Publishing

Maven Central releases use the Vanniktech Gradle Maven Publish plugin and Central Portal.

Before publishing, make sure:

- The Central Portal account has a verified namespace for `io.github.kiluss2`.
- A GPG signing key is available and its public key has been distributed.
- Central Portal user tokens and signing credentials are configured outside the repository.

Recommended local credentials in `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>
signingInMemoryKey=<ascii-armored-private-gpg-key>
signingInMemoryKeyPassword=<gpg-key-passphrase>
```

Publish and release the signed deployment:

```bash
./gradlew publishAndReleaseToMavenCentral
```

Do not retry blindly if the command times out; inspect Central Portal first because published versions are immutable.

After Central Portal shows `PUBLISHED`, tag the same commit and create a GitHub Release:

```bash
git tag -a v0.2.4 -m "Release v0.2.4"
git push origin v0.2.4
gh release create v0.2.4 \
  --title "AndroidAdsDebugKit v0.2.4" \
  --notes "Add deterministic FORCE_FAIL request resolution for MAX and a request-settings revision guard."
```

Do not republish an existing version. Bump the version for every subsequent release.

## License

MIT
