# iTwin mobile-sdk-android

Copyright © Bentley Systems, Incorporated. All rights reserved. See [LICENSE.md](./LICENSE.md) for license terms and full copyright notice.

## Warning

This is pre-release software and provided as-is.

## About This Repository

This repository contains the Kotlin code used to build [iTwin.js](http://www.itwinjs.org) applications on Android devices. This package requires Android API Level 31 (Android 12.0) or later. Most people will use binary builds of this package. If you want to build it yourself in order to contribute, please see [CONTRIBUTING.md](./CONTRIBUTING.md).

## WebView Bridge Origins

`ITMApplication.getBridgeAllowedOrigins()` configures trusted origins for native WebView bridges.
The packaged frontend origin, `https://appassets.itwinjs.org`, and the origin returned by
`getBaseUrl()` are trusted by default. This includes remote frontends configured through
`ITMAPPLICATION_BASE_URL` or a `getBaseUrl()` override. Invalid or unsupported URLs add no origin.
Override this policy in your application subclass to authorize additional origins:

```kotlin
override fun getBridgeAllowedOrigins(): Set<String> =
	super.getBridgeAllowedOrigins() + "https://frontend.example.com"
```

Use exact origins without paths, credentials, wildcards, or default ports. Development origins
should only be added in development builds. Standalone loggers accept an explicit origin set;
`ITMBridgeOriginPolicy` provides shared defaults and origin validation. This policy controls
console forwarding, geolocation, and frontend messaging.

### Frontend messaging

`ITMMessenger` requires `WEB_MESSAGE_LISTENER` and a session-aware `@itwin/mobile-sdk-core`
frontend.

Only the configured main-frame origin may establish a document session or send queries and responses.
Navigation, detach, replacement, and renderer loss fail pending native work and reset readiness;
frontend page teardown rejects its pending Promises. Late responses cannot enter a new session.
There are at most 128 pending queries per direction and 100 incoming native-handler queries per second.

For standalone messengers, call `attach(webView, allowedOrigins)` before loading the frontend,
forward navigation to `onPageStarted(webView)` and `onPageFinished(webView)`, and call `detach()`
before destroying or replacing the WebView, including after renderer loss. Attaching to an
already loaded WebView requires a new load/reload so WebView can inject the scoped listener object.
`ITMApplication` integrates these callbacks. Missing session-aware frontend support produces a
native diagnostic; requests without a current document session are rejected.

### Geolocation

Geolocation requires a WebView provider supporting `WEB_MESSAGE_LISTENER` and a session-aware
`@itwin/mobile-sdk-core` frontend. The native bridge accepts requests only within the authorized
document session.

Use the `navigator.geolocation` callback API to request locations and manage watches.
`watchPosition()` returns its ID synchronously. `clearWatch()` removes its callbacks
immediately and enqueues native cancellation, returning `undefined`; native cancellation errors
are asynchronous. Navigation and detach cancel the document's watches and lookups.

For standalone managers, call `attach(webView, allowedOrigins)` before loading the frontend and
forward navigation start and completion to `onPageStarted(webView)` and `onPageFinished(webView)`.
Call `detach()` when replacing or destroying the WebView. `ITMApplication` provides these hooks.

## Note
This package is designed to be used with the [@itwin/mobile-sdk-core](https://github.com/iTwin/mobile-sdk-core) and [@itwin/mobile-ui-react](https://github.com/iTwin/mobile-ui-react) packages. Those two packages are intended to be installed via npm, and their version number must match the version number of this package. Furthermore, they use __iTwin.js 5.15.0__, and your app must use that same version of iTwin.js.
