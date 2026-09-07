# NotificationsKit — Android/iOS/Common Integration Guide
[![](https://jitpack.io/v/Pentabit-Labs-LLC/notificationskit.svg)](https://jitpack.io/#Pentabit-Labs-LLC/notificationskit)

Shared FCM/ADM → CMS notification pipeline used across Pentabit's apps (expense-tracker-kmm,
CloudStorageKMM, caller-theme-android, wallpaper-app, …). This guide covers everything a
consuming app needs: adding the dependency, the Remote Config keys it expects, the public API,
and integration sketches for both Android and iOS.

NotificationsKit ships through three independent channels, same source, pick whichever fits your
app: a prebuilt AAR (Android-only apps), a CocoaPod (iOS-only, source-built), or — for KMM apps —
a single multiplatform Maven coordinate you add once to `commonMain` and Gradle resolves the right
binary per target. Its source is maintained privately; this repo (and GitHub Packages, for the
common option) carries only compiled artifacts. See "Releasing" in the private source repo if
you're a maintainer publishing a new version, not integrating it.

## 1. Dependencies

### Android

```kotlin
// settings.gradle.kts or the module's repositories block
repositories {
    maven("https://jitpack.io")
}

// app-level build.gradle.kts
dependencies {
    implementation("com.github.Pentabit-Labs-LLC:notificationskit:${LATEST_VERSION}")
}
```
Replace `${LATEST_VERSION}` with the latest tag from this repo's releases (or the JitPack badge
above), e.g. `0.1.0.0`. NotificationsKit is published as a raw `.aar` (not a dependency-aware
Maven module), so none of its third-party dependencies come along transitively — Ktor, Koin, and
kotlinx-coroutines/-serialization all need to be declared explicitly in your app module at
versions compatible with the ones this AAR was built against (see "Versions to keep aligned"
below).

### iOS

```ruby
# Podfile
pod 'notificationskit', :git => 'git@bitbucket.org:pentabitlabs/notification-kit-kmm.git', :tag => '0.1.0.0'
```
`pod install` produces the `NotificationsKit` framework for Swift to `import NotificationsKit`.

Use the Android and iOS options above if your app isn't a Kotlin Multiplatform project (or you'd
rather wire each platform separately). If it *is* KMM and you want one dependency instead of two,
use the option below.

### Common (KMM apps — one dependency for both platforms)

This is a real multiplatform publish (not the prebuilt AAR above) — Gradle resolves the correct
binary (Android `.aar` or the matching iOS klib) per target automatically from a single
coordinate. Building the iOS klibs needs a macOS/Xcode toolchain, so this is published by hand
from a Mac (see the private repo's README for the release process), not built automatically —
unlike the Android AAR channel, there's no JitPack badge tracking this one.

It's hosted as a plain flat Maven repo on GitHub Pages, not a package registry — just static
files over HTTPS, same as Maven Central serves real published libraries. No token, no login, no
`gradle.properties` entry needed to depend on it:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://pentabit-labs-llc.github.io/notificationskit/maven-repo")
        }
    }
}
```

```kotlin
// shared/commonMain module's build.gradle.kts
sourceSets {
    val commonMain by getting {
        dependencies {
            implementation("com.pentabit.notificationskit:notificationskit:0.1.0.0")
        }
    }
}
```

That's the entire dependency wiring for both platforms — no separate JitPack `maven()` entry, no
CocoaPods `pod` line, and none of Ktor/Koin/coroutines/serialization need declaring separately
either (unlike the raw-AAR Android path below, this is a real Maven module with transitive
dependencies resolved normally). The public API, ownership boundary, and Remote Config keys
further down this doc are identical regardless of which of the three channels you used to add the
dependency.

## 2. Ownership boundary

This is the split this module was built around — read it before wiring anything up.

| | Owns |
|---|---|
| **Host app** | Firebase/ADM SDK setup, deciding *when* a notification was opened/dismissed/converted, iOS `UNUserNotificationCenterDelegate` wiring, deep-link resolution (`PendingIntent`s) |
| **NotificationsKit** | Its own Remote Config fetch, vault bearer decrypt, ApiVault "content" key fetch, `/device-api` registration (dedup-checked), `/notification-events` reporting — **and, optionally on Android/Fire OS**, token registration + building the visual notification, via [`FCMNotificationService`/`ADMNotificationService`](#4b-optional-fcmnotificationservice--admnotificationservice) |

Concretely:

- **Getting the token is still the host's job either way** — this module never calls
  `FirebaseMessaging.getInstance()` or ADM's registration APIs itself. What changed: on
  Android/Fire OS you can now let `FCMNotificationService`/`ADMNotificationService` (§4b) *forward*
  the token to `NotificationsKit.registerToken` for you, instead of writing that one line
  yourself. iOS still has no equivalent — call `registerToken` from your own delegate, same as
  before.
- **iOS notification-tap handling is the host's job**, not this module's. Wire
  `UNUserNotificationCenterDelegate` in your own `AppDelegate.swift` and call
  `NotificationsKit.reportOpened(nid)` / `reportConverted(nid)` from there. Note iOS has no
  "swiped away" delegate callback, so a host app may simply never call `reportDismissed` on iOS —
  that's expected, not a gap in this module.
- **This module fetches its own Remote Config** — you don't pass it any decrypted keys or URLs.
  It reads six fixed key *names* (below) straight from Firebase Remote Config, using the same
  `FirebaseRemoteConfig`/`FIRRemoteConfig` singleton your app already initializes.
- **Only the "content" vault category.** If your app also needs the "ai" category key (e.g. for
  receipt scanning), that stays your app's own vault call — this module deliberately doesn't
  fetch it.
- **Notification building (channel, image, `BigPictureStyle`) is now optional-in, Android/Fire OS
  only** — see §4b. `PendingIntent` attachment (and therefore deep-link resolution) stays entirely
  host-side even when using the base classes — the module only assembles the notification, never
  decides what tapping/dismissing it does.
- **iOS has no equivalent of §4b** — no base class, no notification-building helper. Rich iOS
  notifications need a Notification Service Extension (a separate Xcode target), which is a
  materially different integration shape; not covered by this module yet.

## 3. Remote Config keys this module reads

Same names, every app — only the *values* differ. Nothing vault- or CMS-URL-related is hardcoded
to one environment in this module — both base URLs come from Remote Config, same as the key/salt
pairs. Set all six of these in your app's Firebase Remote Config before calling `init()`:

| Key | Used for |
|---|---|
| `VAULT_BASE_URL` | ApiVault base URL (e.g. `https://apivault.pentabitlabs.com`) — this module appends the fixed `/api/public/v1/keys` path itself |
| `PBL_VAULT_KEY_AND` / `PBL_VAULT_SALT_AND` | Android vault bearer (encrypted key + AES salt) |
| `PBL_VAULT_KEY_IOS` / `PBL_VAULT_SALT_IOS` | iOS vault bearer |
| `CMS_BASE_URL` | Base URL for `/device-api` and `/notification-events` |

## 4. Public API

```kotlin
enum class StorePlatform { GOOGLE, AMAZON, IOS }
data class NotificationsKitConfig(val storePlatform: StorePlatform = StorePlatform.GOOGLE)

object NotificationsKit {
    suspend fun init(context: PlatformContext, config: NotificationsKitConfig = NotificationsKitConfig())
    suspend fun registerToken(token: String, language: String? = null)
    suspend fun reportOpened(nid: String)
    suspend fun reportDismissed(nid: String)
    suspend fun reportConverted(nid: String)
}
```

`PlatformContext` wraps `android.content.Context` on Android — construct with
`PlatformContext(applicationContext)` — and is an empty marker on iOS (`PlatformContext()`).
It's a small wrapper class rather than a typealias so it stays a concrete type on both
platforms (Kotlin requires an `expect` class's modality to exactly match every `actual`, and
`Context` itself is `abstract`).

`StorePlatform` is how `/device-api`'s `platform` field ends up `"android"` / `"amazon"` /
`"ios"`. Pass `GOOGLE` or `AMAZON` on Android (which of the two can't be auto-detected — it's a
build-flavor decision your own Gradle `productFlavors` make) and `IOS` on iOS. The module
double-checks this against the actual compile target and will log a warning and fall back to the
compile-detected platform if the two disagree, so a wrong value here can never make the module
report the wrong OS to the CMS — but it should still be set correctly.

### Android integration sketch

```kotlin
// Application.onCreate(), after Firebase is initialized
CoroutineScope(Dispatchers.IO).launch {
    NotificationsKit.init(PlatformContext(applicationContext), NotificationsKitConfig(storePlatform = StorePlatform.GOOGLE))
    // Amazon/Fire OS build flavor: StorePlatform.AMAZON instead.
}

// Host's own FirebaseMessagingService
override fun onNewToken(token: String) {
    CoroutineScope(Dispatchers.IO).launch { NotificationsKit.registerToken(token) }
}

// Host's NotificationClickReceiver
CoroutineScope(Dispatchers.IO).launch { NotificationsKit.reportOpened(nid) }
```
(Or extend `FCMNotificationService`/`ADMNotificationService` instead of writing the above by hand
— see §4b.)

### iOS integration sketch

```swift
// AppDelegate, after FirebaseApp.configure()
Task {
    let config = NotificationsKitConfig(storePlatform: .ios)
    try? await NotificationsKit.shared.init(context: PlatformContext(), config: config)
}

func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
    guard let token = fcmToken else { return }
    Task { try? await NotificationsKit.shared.registerToken(token: token, language: nil) }
}
```
(Exact Swift call shape depends on how Kotlin/Native exports suspend functions in your setup —
matches the existing `FcmTokenSyncBridge` convention if you'd rather add a non-suspend
Swift-facing wrapper object later.)

## 4b. Optional: FCMNotificationService / ADMNotificationService

Android/Fire OS only. Extend one of these instead of `FirebaseMessagingService`/
`ADMMessageHandlerBase` directly, and the module handles token registration and building the
visual notification for you — you only implement four small overrides and attach your
`PendingIntent`s.

**FCM:**
```kotlin
class MyFcmService : FCMNotificationService() {
    override fun notificationChannelId() = "default"
    override fun notificationChannelName() = "Notifications"
    override fun notificationSmallIcon() = R.drawable.ic_notification

    override fun onNotificationBuilt(payload: PushPayload, builder: NotificationCompat.Builder) {
        val nid = payload.data["nid"] ?: return
        builder.setContentIntent(buildClickPendingIntent(nid))   // your own deep-link resolution
               .setDeleteIntent(buildDismissPendingIntent(nid))
        NotificationManagerCompat.from(applicationContext).notify(nid.hashCode(), builder.build())
    }
}
```
Manifest: the usual `<service android:name=".MyFcmService">` with the FCM `<intent-filter>` —
nothing new there, just point it at your subclass instead of a plain `FirebaseMessagingService`.

**ADM (Fire OS):**
```kotlin
class MyAdmService : ADMNotificationService(MyAdmService::class.java.name) {
    override fun notificationChannelId() = "default"
    override fun notificationChannelName() = "Notifications"
    override fun notificationSmallIcon() = R.drawable.ic_notification

    override fun onNotificationBuilt(payload: PushPayload, builder: NotificationCompat.Builder) {
        val nid = payload.data["nid"] ?: return
        builder.setContentIntent(buildClickPendingIntent(nid))
               .setDeleteIntent(buildDismissPendingIntent(nid))
        NotificationManagerCompat.from(context).notify(nid.hashCode(), builder.build())
    }
}
```
The `MyAdmService::class.java.name` argument matches Amazon's own documented pattern for
`ADMMessageHandlerBase` — required so Amazon's reflection-based dispatch (which needs a public
no-arg constructor on your concrete class) still works. Your app still needs the usual ADM
manifest entries (`amazon.device.messaging.permission.RECEIVE`, the `ADM_MESSAGE_HANDLER`
meta-data, etc.) — this only changes what the handler class itself does.

**Amazon ADM setup — read before using `ADMNotificationService`:** Amazon doesn't publish the ADM
SDK to a Maven repository, only as a downloadable jar. Your app needs
`amazon-device-messaging-1.2.0.jar` in its own `libs/` folder and a
`compileOnly(files("libs/amazon-device-messaging-1.2.0.jar"))` dependency — same manual step every
ADM integration requires, `compileOnly` dependencies aren't transitive so this module bundling its
own copy doesn't cover your app too. Download: https://developer.amazon.com/docs/adm/overview.html#download
(requires an Amazon developer account).

Both base classes only ever *build* the notification — they never call
`NotificationManagerCompat.notify()` themselves. Picking a notification id, attaching
`PendingIntent`s (deep-link resolution), calling `.build()`, actually posting it (or choosing not
to), and calling `reportOpened`/`reportDismissed`/`reportConverted` from your own click/dismiss
receivers all stay entirely your own code, unchanged from integrating without these classes at
all — exactly as shown in the two examples above. Skip §4b entirely and keep writing your own
`FirebaseMessagingService`/`ADMMessageHandlerBase` + calling `registerToken` directly if you'd
rather have full control over notification building, or need to do other things in that service
beyond notifications.

## 5. Versions to keep aligned

The AAR/framework was built against: Kotlin 2.2.21, Ktor 3.3.3, Koin 4.1.1 (`koin-core`),
kotlinx-coroutines 1.10.2, kotlinx-serialization 1.9.0, compileSdk/minSdk 36/24. Declare compatible
versions of these explicitly in your own app module — see §1's note on this being a raw AAR with
no transitive dependency resolution.

The iOS side needs `FirebaseRemoteConfig ~> 12.2.0` (or whatever version this build was pinned to
— check the tag's release notes) available via your own Podfile; Firebase is a static xcframework,
so two different versions in the same binary will crash at runtime with duplicate ObjC class
registration.

## 6. Known gaps

- `ADMNotificationService` hasn't been verified against a real Fire OS build — no ADM/Fire OS
  environment was available to test it in. It follows Amazon's documented API shape in good faith;
  treat it as needing a real-device check before shipping.
- Notification building beyond what §4b's base classes cover (custom layouts, action buttons,
  grouped/summary notifications) still needs your own code — the base classes hand you a
  `NotificationCompat.Builder` you can keep customizing before calling `.build()`.
- iOS has no equivalent of §4b at all (no base class, no notification-building helper) — see §2.
  It also has no "swiped away" delegate callback, so `reportDismissed` may simply never be called
  there — expected, not a bug.
