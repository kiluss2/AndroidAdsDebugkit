# AndroidAdsDebugKit

AndroidAdsDebugKit is an in-app debug panel for inspecting ads, ad revenue, external tracking logs, and runtime ad unit overrides in debug or release builds.

It is designed for production QA flows where testers need to enable a hidden debug panel from inside the app, inspect real ad states, force ad-load failures, test AdMob-only fallback, and verify external SDK tracking without rebuilding the app.

## Features

- Hidden unlock gesture for release builds.
- Floating near-fullscreen debug sheet that survives Activity navigation.
- Ad state dashboard for real GMA ads: load, show/impression, revenue.
- External tracking log tab for Adjust, Meta/Facebook, TikTok, AppsFlyer, and similar SDK logs.
- Custom event tab for app-defined QA/debug events.
- Runtime ad unit override modes: normal, fail primary, fail all, force AdMob-only, custom per placement.
- Automatic ad unit discovery from `R.string` resources matching `ads_*_id`.
- Structured Timber parser for ads and external logs.
- R8 consumer rules for resource-name discovery.

## Installation

### Maven Central

After the first public release:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("fxc.dev:android-ads-debug-kit:0.1.0")
}
```

### Local Development

Publish the library to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then use the local release dependency from an app:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("fxc.dev:android-ads-debug-kit:0.1.0")
}
```

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

This means app ads use the normal `configs.xml` / production configuration unless a tester explicitly enables the hidden debug mode.

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

The placement is important because multiple placements can share the same AdMob ad unit ID, but DebugKit still needs to track and override each placement independently.

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

## Runtime Ad Unit Override

Before loading an ad, resolve the ad unit through the kit:

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

### Override Modes

- `NORMAL`: use configured app IDs.
- `FAIL_PRIMARY`: priority placements such as `_2F_id` and `_MF_id` use invalid IDs; normal and AdMob-only IDs stay configured.
- `FAIL_ALL`: all overridable ad unit requests use an invalid ID.
- `FORCE_ADMOB_ONLY`: primary requests fail, AdMob-only fallback requests use configured backup IDs.
- `CUSTOM`: each placement can be set to `Release`, `Debug`, or `False` from the Ad Units tab.

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

DebugKit also taps filtered logcat lines for selected SDK outputs such as Adjust response strings and Meta/Facebook flush results while debug mode is enabled.

For reliable provider status, emit `external_debug=1` directly from the app tracking layer at the point each SDK is initialized or receives a callback. Do not rely only on SDK logcat output because some SDKs, especially Meta/Facebook, do not expose a stable init-success log line.

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
        backgroundDrawableResId = R.drawable.ads_debug_background
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
- `DebugComboGestureHelper`
- `AdDebugConfig`
- `AdDebugAdUnit`
- `AdDebugUnit`
- `AdIdRequestRole`
- `AdsDebugLogFormat`

Implementation details such as the window manager, panel view, logcat tap, and Timber parser are internal.

## Verification

Library checks:

```bash
./gradlew compileReleaseKotlin lintRelease publishToMavenLocal
```

Consumer app checks:

```bash
./gradlew :app:compileDebugKotlin :app:minifyReleaseWithR8
```

## Publishing

Maven Central releases use the Vanniktech Gradle Maven Publish plugin and Central Portal.

Before publishing, make sure:

- The Central Portal account has a verified namespace for `fxc.dev`.
- A GPG signing key is available and its public key has been distributed.
- Central Portal user tokens and signing credentials are configured outside the repository.

Recommended local credentials in `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>
signingInMemoryKey=<ascii-armored-private-gpg-key>
signingInMemoryKeyPassword=<gpg-key-passphrase>
```

Use the manual release flow first:

```bash
./gradlew clean lintRelease publishToMavenCentral
```

Then open Central Portal deployments, inspect validation, and publish the deployment manually.

After the release is validated, tag the same commit:

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

MIT
