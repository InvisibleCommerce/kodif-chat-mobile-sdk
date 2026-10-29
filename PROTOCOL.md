# Bridge protocol (v1)

The SDKs host the Kodif chat widget (the `chat-widget.js` bundle that runs on storefronts) full screen in a WebView.
This file is the contract between that bundle's **mobile mode** and the native code. A change here ships in lockstep
with the widget (`shipped-suite-js-client-sdk`, `src/chat-widget/services/native-bridge.ts`).

## The page

iOS loads the page with `loadHTMLString(_:baseURL:)` on `https://mobile-chat.kodif.ai/`; Android serves it through
`WebViewAssetLoader` on `https://appassets.androidplatform.net/`. The origin is https because voice mode's microphone
needs a secure context.

```html
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
  <script>
    window.KodifChatNative = { pending: [], receive: function (m) { this.pending.push(m) } };
    window.addEventListener('shipped:chat-script-loaded', function () {
      new window.ShippedAI.ChatWidget(OPTIONS)
    });
    var bundle = document.createElement('script');
    bundle.type = 'module';
    bundle.src = BUNDLE_URL;
    bundle.onerror = function () { /* post {"v":1,"type":"unavailable"} to the native handler */ };
    document.head.appendChild(bundle);
  </script>
</head>
<body></body>
</html>
```

- **The page buffers.** Its script first defines `window.KodifChatNative`, which collects messages in `pending`. The
  widget replaces it when it starts and replays `pending`. So native never queues or waits: it always evaluates
  `window.KodifChatNative.receive(<message JSON>)`.
- `OPTIONS` is JSON: `integrationId` (string), `host` (`'ios'` \| `'android'`, turns on mobile mode),
  `hostCapabilities` (`{ microphone: boolean }`, true when the app opted into voice), and an optional `language`
  (e.g. `fr`; omitted, the widget detects it). `OPTIONS` and `BUNDLE_URL` (a JSON string) escape `<` as `\u003c`.
- The bundle's `onerror` posts `{"v":1,"type":"unavailable"}` to the native handler
  (`window.webkit.messageHandlers.kodifChat`, else `window.kodifChat`), because a bundle that fails to load (offline,
  a CDN error) never starts the widget. The page inserts the module script itself: in WKWebView (iOS 18) a failed
  module script written in the HTML didn't fire `error`, while one inserted from script does.
- `BUNDLE_URL`: production `https://js.shippedsuite.com/api/chat-widget.js`, staging
  `https://js-staging.shippedsuite.com/api/chat-widget.js`, development
  `https://js.dev.invisiblecommerce.com/api/chat-widget.js`, or any URL (tests).

In mobile mode the widget is always open and full screen, with no launcher. It never runs campaigns, and it never
marks conversations as tests by the page's origin.

**Safe areas.** iOS keeps the web view edge to edge (scrolling and bounce off, its bottom on the keyboard's top while
the keyboard is up), and the widget pads its header and composer with `env(safe-area-inset-*)`. Android pads the
WebView's container by the system bar, cutout and keyboard insets natively, so the page sees no insets.

**Links** leave the page only through native navigation interception: iOS `decidePolicyFor` and
`createWebViewWith`, Android `shouldOverrideUrlLoading` with `setSupportMultipleWindows(false)`. Native gives the URL
to the app, or opens it in SFSafariViewController / a Custom Tab. The widget renders ordinary links
(`target="_blank"` included) and doesn't intercept clicks.

## Messages

Every message is a JSON object `{ "v": 1, "type": "...", ... }`. The widget posts with
`postMessage(JSON.stringify(message))` on `window.webkit.messageHandlers.kodifChat` (iOS), else `window.kodifChat`
(Android, `WebViewCompat.addWebMessageListener` restricted to the page's origin).

| Widget → native | fields | native does |
|---|---|---|
| `requestToken` | `id: string` (random) | Asks the app's token provider for the shopper's JWT and answers with `token`. |
| `close` | | The shopper tapped close. Dismiss the chat. |
| `reply` | | A reply finished. Native counts it as unread unless the chat is on screen with the app in the foreground. |
| `openSettings` | | The microphone permission is denied. Open the app's settings page. |
| `unavailable` | | Switched off for this store, or failed to start (also posted by the page's `onerror`). Dismiss, tell the app, and reload on the next preload or open. |

| Native → widget | fields | widget does |
|---|---|---|
| `token` | `id: string`, `jwt: string \| null` | Answers `requestToken` (`null`: anonymous). Never sent unasked. |
| `appState` | `state: 'foreground' \| 'background'` | In the background the widget leaves a voice call (and rejoins on foreground, if it's still going). |
| `customContext` | `context: object \| null` | Replaces the conversation's custom context (`null` clears it). Sent after every page load when the app has set one, and whenever the app changes it. |

When the app's shopper signs in or out, native reloads the page (on sign-out, after clearing the page origin's stored
data), and the widget asks `requestToken` again. Native also reloads after a web content process crash.

## Versioning

`v` is the protocol version. The widget ignores messages with a `v` it doesn't know, and native ignores unknown types,
so either side can add a message type without breaking the other. Changing a message's meaning bumps `v`.
