# Kodif Chat for iOS and Android

Kodif Chat puts your store's Kodif chat in your mobile app. It's a thin native wrapper around the same chat widget
that runs on your website: the widget runs full screen in a web view, and the SDK connects it to your app for
sign-in, links, voice mode, attachments, and unread-reply notifications.

- iOS 15+, Swift Package Manager: `KodifChat`
- Android 7.0+ (API 24): `ai.kodif:chat`

You need the **integration id** of your web chat channel (in your Kodif dashboard's web chat settings).

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
KodifChat.preload() // optional: starts the chat in the background so it opens instantly

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

To connect the chat to the shopper's account, give the SDK a JWT for them, signed on **your backend** with the
secret from **the Custom Authentication tab of your web chat settings**. Never ship that secret in the app.

```swift
KodifChat.tokenProvider = { completion in
    // Call completion from any thread: a JWT for a signed-in shopper, nil for an anonymous one.
    MyBackend.fetchChatToken { jwt in completion(jwt) }
}
```

The chat asks for a token when it starts and again when one expires. When the shopper signs in or out while the chat
is running, tell it:

```swift
KodifChat.setShopperToken(jwt) // after sign-in
KodifChat.setShopperToken(nil) // after sign-out
```

Keep `tokenProvider` in step with the shopper's state too: it answers every request the chat makes.

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
    // A link was tapped. Return true if you handled it (e.g. a deep link to a product screen);
    // false opens it in an in-app Safari view.
    func kodifChat(openURL url: URL) -> Bool { false }

    // The shopper closed the chat.
    func kodifChatDidClose() {}

    // A reply arrived while the chat wasn't on screen: show a badge.
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author) {}

    // The chat is switched off for your store, or failed to start. It has been dismissed.
    func kodifChatUnavailable() {}
}

KodifChat.delegate = chatEvents // held weakly: keep a reference
```

Every method is optional.

### Other options

```swift
KodifChat.language = "fr"                                  // otherwise the chat detects it
KodifChat.customContext = ["screen": "product", "sku": "123"] // attached to the conversation; updates a running chat
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
    implementation("com.github.InvisibleCommerce:kodif-chat-mobile:<tag>")
}
```

Maven Central (`ai.kodif:chat:<version>`) is coming soon.

### Configure and show the chat

```kotlin
// Application.onCreate
KodifChat.configure(this, "YOUR_INTEGRATION_ID")
KodifChat.preload() // optional: starts the chat in the background so it opens instantly

// From an activity
KodifChat.open(this)
```

`open` shows the chat full screen in `KodifChatActivity`, which is edge to edge, keeps the chat clear of the system
bars, and resizes with the keyboard. To embed the chat in your own screen instead, add a `KodifChatFragment`, and
remove it in `KodifChatListener.onClose`.

### Signed-in shoppers

Sign a JWT for the shopper on **your backend** with the secret from **the Custom Authentication tab of your web chat
settings** (never ship that secret in the app), and provide it:

```kotlin
KodifChat.tokenProvider = TokenProvider { callback ->
    // From any thread: a JWT for a signed-in shopper, null for an anonymous one.
    myBackend.fetchChatToken { jwt -> callback(jwt) }
}

KodifChat.setShopperToken(jwt)  // after sign-in, while the chat is running
KodifChat.setShopperToken(null) // after sign-out
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
    override fun onMessage(preview: String, author: KodifChat.Author) {} // a reply arrived while the chat was hidden
    override fun onUnavailable() {} // switched off for your store, or failed to start
}
```

Every method is optional. The listener is held strongly: set it to null when its owner goes away.

### Other options

```kotlin
KodifChat.language = "fr"
KodifChat.customContext = mapOf("screen" to "product", "sku" to "123")
KodifChat.configure(context, "…", KodifChat.Environment.Staging) // Production (default), Staging, Development
```

## How it works

The SDK keeps one web view for the chat, loads a small page that starts the Kodif chat widget in mobile mode, and
reuses it every time the chat opens, so a conversation (and a voice call) survives closing and reopening the chat.
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
