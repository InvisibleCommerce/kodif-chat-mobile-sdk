# Kodif Chat for iOS and Android

Kodif Chat puts your store's Kodif chat in your mobile app. It's a thin native wrapper around the same chat widget
that runs on your website: the widget runs full screen in a web view, and the SDK connects it to your app for
sign-in, links, voice mode, attachments, and unread-reply notifications.

- iOS 15+, Swift Package Manager: `KodifChat`
- Android 7.0+ (API 24): `ai.kodif:chat`

You need the **integration id** of your web chat channel (in your Kodif dashboard's web chat settings).

## Shopper tokens

Without a token the chat is anonymous. To connect it to a signed-in shopper's account, the app gives the chat a JWT
for them, which **your backend** issues:

- Your backend signs the JWT with **your own private key**, using RS256 (an RSA key of 2048 bits or more) or ES256
  (a P-256 key). The private key never leaves your servers, and the app never signs or mints tokens itself.
- On the **Custom Authentication** tab of your web chat settings, give Kodif the matching **public key**: paste it
  as a PEM, or enter an HTTPS **JWKS URL** that Kodif fetches. With a JWKS URL the key is chosen by the token's
  `kid`, so you can rotate keys.
- The token carries the shopper's id in the **identity claim** you set on that tab (`identity` by default; `sub` or
  `email` are common), and an **`exp`**. Keep tokens short-lived (minutes): the app fetches a new one whenever the
  chat asks. An `exp` is always checked when present, and the tab can require every token to have one.
- Optionally, set an **audience** and/or **issuer** on the tab: tokens must then carry a matching `aud` / `iss`.
- A token that's missing, expired or fails verification doesn't sign anyone in: the chat carries on as an anonymous
  shopper.

In the app, the **token provider** fetches the current shopper's token from your backend when the chat asks for one
(when it starts, and again when a token expires). When the shopper signs in or out while the chat is running,
`setShopperToken` tells the chat right away: a JWT signs the chat in with that token directly, and `nil` / `null`
starts a fresh anonymous shopper (the previous shopper's conversations are no longer reachable on the device). Keep
the token provider in step with the shopper's state too, since it answers every request the chat makes.

## iOS

### Install

In Xcode, choose **File → Add Package Dependencies…**, enter `https://github.com/InvisibleCommerce/kodif-chat-mobile`,
and add the `KodifChat` product to your app. In `Package.swift`:

```swift
.package(url: "https://github.com/InvisibleCommerce/kodif-chat-mobile", from: "0.1.0"),
// ...
.product(name: "KodifChat", package: "kodif-chat-mobile"),
```

### Configure and show the chat

Configure once at launch, then present the chat:

```swift
import KodifChat

// At launch (application(_:didFinishLaunchingWithOptions:) or your App's init):
KodifChat.configure(integrationId: "YOUR_INTEGRATION_ID")

// Optional, from your first screen (e.g. viewDidAppear / .onAppear), not at launch: starts the chat in the
// background so it opens instantly. It waits for the next turn of the main run loop, so it doesn't hold up the screen.
KodifChat.preload()

// UIKit
KodifChat.present(from: viewController)
// or present it yourself:
viewController.present(KodifChatViewController(), animated: true)
```

SwiftUI:

```swift
struct ContentView: View {
    @State private var showChat = false

    var body: some View {
        Button("Chat with us") { showChat = true }
            .fullScreenCover(isPresented: $showChat) {
                KodifChatView().ignoresSafeArea()
            }
    }
}
```

The chat is shown edge to edge and handles the safe area and the keyboard itself. When the shopper closes it, it
dismisses itself.

### Signed-in shoppers

Fetch the shopper's token from your backend (see [Shopper tokens](#shopper-tokens)):

```swift
KodifChat.tokenProvider = { completion in
    // Call completion from any thread: a JWT from your backend for a signed-in shopper, nil for an anonymous one.
    MyBackend.fetchChatToken { jwt in completion(jwt) }
}
```

When the shopper signs in or out while the chat is running, tell it:

```swift
KodifChat.setShopperToken(jwt) // after sign-in: the chat signs in with this token
KodifChat.setShopperToken(nil) // after sign-out: a fresh anonymous shopper
```

### Voice mode

Voice mode is off by default. To offer it:

```swift
KodifChat.voiceEnabled = true // before preload() / presenting the chat
```

and add `NSMicrophoneUsageDescription` to your Info.plist. **This is required**: iOS terminates the app when the chat
asks for the microphone without it. If the shopper has denied microphone access, the chat offers to open your app's
settings.

### Attachments

Shoppers can attach photos and files. Add these to your Info.plist so they can use the camera and photo library:

- `NSCameraUsageDescription`
- `NSPhotoLibraryUsageDescription`

### Events

```swift
final class ChatEvents: KodifChatDelegate {
    // A link was tapped (any link that leaves the chat, including ones that open a new window). Return true if
    // you handled it (e.g. a deep link to a product screen); false opens it in an in-app Safari view.
    func kodifChat(openURL url: URL) -> Bool { false }

    // The shopper closed the chat.
    func kodifChatDidClose() {}

    // A reply arrived while the shopper wasn't looking (the chat isn't on screen, or the app is in the
    // background): show a badge.
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author) {}

    // The chat is switched off for your store, or failed to start (e.g. offline). It has been dismissed;
    // the next preload or presentation tries again.
    func kodifChatUnavailable() {}
}

KodifChat.delegate = chatEvents // held weakly: keep a reference
```

Every method is optional.

### Other options

```swift
KodifChat.language = "fr"                                  // otherwise the chat detects it
KodifChat.customContext = ["screen": "product", "sku": "123"] // attached to the conversation; replaces it (nil clears)
KodifChat.configure(integrationId: "…", environment: .staging) // .production (default), .staging, .development
```

## Android

### Install

From JitPack: add the repository and the dependency.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.InvisibleCommerce:kodif-chat-mobile:0.1.0")
}
```

Maven Central (`ai.kodif:chat:<version>`) is coming soon.

### Configure and show the chat

```kotlin
// Application.onCreate
KodifChat.configure(this, "YOUR_INTEGRATION_ID")

// Optional, from your first screen (e.g. its onCreate), not at launch: starts the chat in the background so it
// opens instantly. The WebView is created once the main thread is idle, so it doesn't hold up the screen.
KodifChat.preload()

// From an activity
KodifChat.open(this)
```

`open` shows the chat full screen in `KodifChatActivity`, which is edge to edge, keeps the chat clear of the system
bars, and resizes with the keyboard. To embed the chat in your own screen instead, add a `KodifChatFragment`, and
remove it in `KodifChatListener.onClose`.

### Signed-in shoppers

Fetch the shopper's token from your backend (see [Shopper tokens](#shopper-tokens)):

```kotlin
KodifChat.tokenProvider = TokenProvider { callback ->
    // From any thread: a JWT from your backend for a signed-in shopper, null for an anonymous one.
    myBackend.fetchChatToken { jwt -> callback(jwt) }
}

KodifChat.setShopperToken(jwt)  // after sign-in, while the chat is running: the chat signs in with this token
KodifChat.setShopperToken(null) // after sign-out: a fresh anonymous shopper
```

### Voice mode

```kotlin
KodifChat.voiceEnabled = true // before preload() / open()
```

and declare in your app's manifest:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

The SDK asks for the microphone permission when the shopper starts a call. The library's own manifest only adds
`INTERNET`.

### Attachments

Shoppers pick files with the system picker, and can take a photo with the camera when the request accepts images.
Camera capture needs no permission, unless your app declares `CAMERA` in its manifest: Android then requires it to
be granted, and the SDK asks for it when the picker opens. Photos are shared with the camera app through the SDK's
`FileProvider` (`${applicationId}.kodifchat.fileprovider`), which is merged into your manifest automatically.

### Events

```kotlin
KodifChat.listener = object : KodifChatListener {
    override fun onOpenUrl(url: String): Boolean = false // true if you handled it; false opens a Custom Tab
    override fun onClose() {}
    override fun onMessage(preview: String, author: KodifChat.Author) {} // a reply the shopper wasn't looking at
    override fun onUnavailable() {} // switched off for your store, or failed to start (e.g. offline); dismissed
}
```

Every method is optional. The listener is held strongly, so set it from something that outlives the chat (your
`Application`), or set it back to `null` in the owning activity's `onDestroy`.

### Other options

```kotlin
KodifChat.language = "fr"
KodifChat.customContext = mapOf("screen" to "product", "sku" to "123") // replaces the context (null clears it)
KodifChat.configure(context, "…", KodifChat.Environment.Staging) // Production (default), Staging, Development
```

## How it works

The SDK keeps one web view for the chat, loads a small page that starts the Kodif chat widget in mobile mode, and
reuses it every time the chat opens, so a conversation (and a voice call) survives closing and reopening the chat.
If the chat can't start (the widget doesn't load, e.g. offline, or doesn't come up within 15 seconds), the SDK
dismisses it and reports it as unavailable, and the next preload or open loads it again.
The widget and the native code talk through a small, versioned message protocol: see [PROTOCOL.md](PROTOCOL.md).

## Development

```sh
# iOS (macOS with Xcode 16)
xcodebuild test -scheme KodifChat -destination 'platform=iOS Simulator,name=iPhone 16'
open ios/Example/KodifChatExample.xcodeproj # after editing ios/Example/project.yml, run `xcodegen generate` there

# Android (JDK 17, Android SDK 35)
cd android
./gradlew :kodifchat:testDebugUnitTest :kodifchat:lint :example:assembleDebug
./gradlew :kodifchat:connectedDebugAndroidTest # with an emulator or device attached
```

The Android example reads `kodif.integrationId` and `kodif.environment` (`production`, `staging`, `development`)
from `android/local.properties`; the iOS example reads `KODIF_INTEGRATION_ID` / `KODIF_ENVIRONMENT` from its build
settings (`ios/Example/project.yml`).

## License

MIT. See [LICENSE](LICENSE).
