# AndroidAdsDebugKit

Android in-app ads debug overlay inspired by the iOS AdsDebugKit flow.

## Install

Local Maven:

```bash
./gradlew publishToMavenLocal
```

App dependency:

```kotlin
implementation("fxc.dev:android-ads-debug-kit:0.0.1-SNAPSHOT")
```

## Initialize

```kotlin
AdsDebugKit.initialize(application)
```

By default, the kit auto-discovers non-empty string resources matching `ads_*_id`.
Use `AdDebugConfig(allAdUnits = { ... })` only when an app has custom ad ids outside that resource naming rule.

## Hidden Unlock

Attach the hidden combo to a splash/app icon view:

```kotlin
DebugComboGestureHelper().setup(appIconView)
```

Gesture: swipe down, double tap, swipe up within 3 seconds. After debug mode is enabled, shake the device to toggle the debug panel.

The debug panel is attached to the current Activity `decorView` and re-attaches when the foreground Activity changes.

## Log Ads

DebugKit reads structured Timber logs when debug mode is enabled. Keep these formats stable. Add a local code comment before ads Timber blocks:

```kotlin
// DO NOT MODIFY ads_debug Timber format unless maintaining AndroidAdsDebugKit parser.
```

Real GMA ads use:

```text
ads_debug=1 event=load_success unit=NATIVE placement=ads_native_language_id adUnit=ca-app-pub-xxx network=AdMob
```

Rules:

- Prefix must be `ads_debug=1`.
- Fields must be `key=value`.
- Required fields: `event`, `unit`, `placement`.
- Use `adUnit`, `network`, `lineItem`, `message`, `valueMicros`, `currency`, `precision` when available.
- Supported events include `load_start`, `load_success`, `load_fail`, `show_start`, `show_success`, `show_fail`, `show_dismissed`, `click`, `impression`, `fallback`, `populate`, and `paid`.
- In-app promo/fallback views are app UI, not ad SDK state. Do not emit `ads_debug=1` for them.

External tracking status uses:

```text
external_debug=1 provider=appsflyer event=purchase status=success message=optional
```

Supported status values: `submitted`, `loading`, `success`, `failed`, `skipped`.

The app should emit these through Timber with one of `AdsDebugLogFormat.Tag.*` so release builds can still be inspected with logcat after the hidden debug mode is enabled.

```kotlin
Timber.tag(AdsDebugLogFormat.Tag.NATIVE).d(
    "${AdsDebugLogFormat.MARKER} event=load_success unit=NATIVE placement=ads_native_language_id adUnit=ca-app-pub-xxx"
)
```

## Runtime Ad ID Override

```kotlin
val adUnitId = AdsDebugKit.resolveAdUnitId(
    placement = "ads_native_language_id",
    primaryAdUnitId = primaryId,
    admobOnlyAdUnitId = backupId,
    role = AdIdRequestRole.PRIMARY
)
```

Supported modes:

- `NORMAL`
- `FAIL_PRIMARY`
- `FAIL_ALL`
- `FORCE_ADMOB_ONLY`
