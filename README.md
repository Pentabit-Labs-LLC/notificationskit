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
| **Host app** | Firebase/ADM SDK setup, obtaining the push token, forwarding it to this module, deciding *when* a notification was opened/dismissed/converted, iOS `UNUserNotificationCenterDelegate` wiring, deep-link resolution |
| **NotificationsKit** | Its own Remote Config fetch, vault bearer decrypt, ApiVault "content" key fetch, `/device-api` registration (dedup-checked), `/notification-events` reporting |

Concretely:

- **Getting the token is the host's job.** FCM: `FirebaseMessaging.getInstance().getToken()` /
  `onNewToken`. ADM (Fire OS): the Amazon SDK's `ADM`/`ADMMessageHandlerBase` classes and their
  manifest receivers. Whichever SDK produced it, the host just calls
  `NotificationsKit.registerToken(token)` — this module doesn't care where the token came from.
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
- Android notification *building* (channel creation, image download, BigPictureStyle, click/dismiss
  PendingIntents) isn't in this module — that stays host-side alongside FCM/ADM token retrieval.
- Real Amazon ADM token retrieval stays host-side too — `registerToken()` accepts any token string
  regardless of source, so this module never needed to touch ADM itself.

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

- Android notification *building* (channel/BigPictureStyle/PendingIntents) and real ADM token
  retrieval are host-side by design — see §2.
- iOS has no "swiped away" delegate callback, so `reportDismissed` may simply never be called
  there — expected, not a bug.
