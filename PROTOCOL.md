# Bridge protocol (v1)

The SDKs host the Kodif chat widget (the same `chat-widget.js` bundle that runs on storefronts) full screen in a
WebView. This file is the contract between that bundle's **mobile mode** and the native code. A change here
ships in lockstep with the widget (`shipped-suite-js-client-sdk`, `src/chat-widget/services/native-bridge.ts`).

## The page

The SDK loads a page it generates. iOS loads it with `loadHTMLString(_:baseURL:)`; Android serves it through
`WebViewAssetLoader` on `https://appassets.androidplatform.net/`. The page's origin must be https, because voice
mode's microphone needs a secure context. The page:

```html
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
  <script>
    window.addEventListener('shipped:chat-script-loaded', function () {
      new window.ShippedAI.ChatWidget(/* the widget options, as JSON */)
    })
  </script>
  <script type="module" src="BUNDLE_URL" onerror="BUNDLE_ERROR_HANDLER"></script>
</head>
<body></body>
</html>
```

**Safe areas.** iOS keeps the WKWebView edge to edge (pinned to the view's edges, the scroll view's
`contentInsetAdjustmentBehavior = .never`, scrolling and bounce disabled; while the keyboard is up the web view's
bottom follows the keyboard's top, so the layout viewport shrinks as on Android), and the widget pads its header and
composer with `env(safe-area-inset-*)`. Android doesn't rely on `env()`: the SDK applies the system bar and IME insets
(`WindowInsetsCompat.Type.systemBars()` + `ime()`) natively as padding on the WebView's container (white background),
so the page itself sees no insets.

`BUNDLE_ERROR_HANDLER` (HTML-escaped into the attribute) posts `{"v":1,"type":"unavailable"}` straight to the native
handler (`window.webkit.messageHandlers.kodifChat`, else `window.kodifChat`), because a bundle that fails to load
(offline, a CDN error) never starts the widget or its bridge. Native also treats a load that hasn't sent `ready`
within 15 seconds as failed. Either way, and on the widget's own `unavailable`, native marks the page failed,
dismisses the chat, tells the app, and loads the page again on the next preload or open (as it does when the web
content process dies).

`BUNDLE_URL` comes from the environment:

| Environment | Bundle |
|---|---|
| production | `https://js.shippedsuite.com/api/chat-widget.js` |
| staging | `https://js-staging.shippedsuite.com/api/chat-widget.js` |
| development | `https://js.dev.invisiblecommerce.com/api/chat-widget.js` |
| custom | any URL (tests) |

### Widget options

| Option | Type | Notes |
|---|---|---|
| `integrationId` | string | Required. The web chat channel's integration id. |
| `host` | `'ios'` \| `'android'` | Required. Turns on mobile mode. |
| `hostCapabilities` | `{ microphone: boolean }` | `microphone` is true when the app opted into voice. False hides voice mode. |
| `language` | string? | Optional locale (e.g. `fr`). Omitted: the widget detects it. |

Custom context isn't a widget option: native sends it as a `customContext` message right after `ready` (when the app
has set one), and again whenever the app changes it. The widget merges runtime context over a constructor value, so
keys passed at construction could never be removed.

In mobile mode the widget is always open and full screen, with no launcher. It never runs campaigns, and it
never marks conversations as tests by the page's origin.

## Messages

Every message is a JSON object `{ "v": 1, "type": "...", ... }`.

**Widget → native.** The widget calls `postMessage(JSON.stringify(message))` on
`window.webkit.messageHandlers.kodifChat` (iOS, a `WKScriptMessageHandler` named `kodifChat`), else on
`window.kodifChat` (Android, `WebViewCompat.addWebMessageListener` named `kodifChat`, restricted to the page's
origin).

| type | fields | native does |
|---|---|---|
| `ready` | | The widget's bridge is listening. Flush the queued native → widget messages. |
| `requestToken` | `id: string` | Ask the app's token provider for a shopper JWT, then answer with `token` and the same `id`. Sent at start, and again when the API rejects an expired token. |
| `close` | | The shopper tapped the header's close button. Dismiss the chat UI. |
| `message` | `preview: string`, `author: 'ai' \| 'agent'` | A reply finished. Sent for every reply; native drops it while the chat is on screen and the app is in the foreground, and otherwise passes it to the app (for an in-app badge). |
| `openSettings` | | The microphone permission is denied. Open the app's system settings page. |
| `unavailable` | | The chat is switched off for this store (or failed to start). Show nothing / dismiss; reload the page on the next preload or open. Also posted by the page itself when the bundle fails to load. |

**Links.** The widget doesn't intercept clicks or post links: links are ordinary `<a href>` elements (a product card,
a link in a reply, a file), `target="_blank"` included. Native intercepts every navigation that would leave the page
and gives the URL to the app's delegate / listener, or opens it in an in-app browser (SFSafariViewController / Custom
Tabs): iOS in `decidePolicyFor` (main-frame navigations) and `createWebViewWith` (new windows, which it never
opens), Android in `shouldOverrideUrlLoading` with `setSupportMultipleWindows(false)` (so new-window links navigate
the WebView and are intercepted the same way). Both let scripts open windows without a tap, since every new window
is routed this way.

**Native → widget.** Native runs `window.KodifChatNative.receive(<message JSON>)` (`evaluateJavaScript` /
`evaluateJavascript`), only after `ready`; queue anything earlier. Several messages may arrive in one script, in
order (`receive(a); receive(b)`), e.g. everything flushed on `ready`.

The widget sends `ready` as soon as its bridge is listening (`window.KodifChatNative` is installed). That happens at
construction, before the chat UI mounts and before `requestToken`, so `ready` means "you can call
`KodifChatNative.receive` now", not "the UI is mounted". `ready` always comes first, and answers to `requestToken`
follow the same rule as every other native → widget message: they're sent after `ready`. The widget times out a
`requestToken` after 10 seconds and carries on anonymous.

| type | fields | widget does |
|---|---|---|
| `token` | `id?: string`, `jwt: string \| null` | Answers `requestToken` (with its `id`). Sent without `id` when the app's shopper logs in or out: a JWT signs the widget in with that token directly (it doesn't send `requestToken` again); `null` starts a fresh anonymous shopper, so the previous shopper's conversations are no longer reachable on the device. |
| `appState` | `state: 'foreground' \| 'background'` | In the background the widget leaves an ongoing voice call (it rejoins on foreground, if the call is still going). |
| `customContext` | `context: object \| null` | Replaces the conversation's custom context (`null` clears it). Native sends it right after `ready` when the app has set one, and whenever the app changes it. |

## Versioning

`v` is the protocol version. The widget ignores messages with a `v` it doesn't know, and native ignores unknown
types, so either side can add a message type without breaking the other. Changing a message's meaning bumps `v`.
