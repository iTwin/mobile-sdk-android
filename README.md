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
console forwarding and geolocation; messaging will adopt it in its security migration.

### Geolocation

Geolocation requires a WebView provider supporting `WEB_MESSAGE_LISTENER` and a session-aware
`@itwin/mobile-sdk-core` frontend. The native bridge rejects the old direct-call protocol; it does
not fall back to an unrestricted JavaScript interface. Updated core retains compatibility with
older native SDKs through transport detection.

The `navigator.geolocation` callback API and existing options behavior are unchanged.
`watchPosition()` still returns its ID synchronously. `clearWatch()` removes its callbacks
immediately and enqueues native cancellation, returning `undefined`; native cancellation errors
no longer throw synchronously. Navigation and detach cancel the document's watches and lookups.

For standalone managers, call `attach(webView, allowedOrigins)` before loading the frontend and
forward navigation start and completion to `onPageStarted(webView)` and `onPageFinished(webView)`.
Call `detach()` when replacing or destroying the WebView. `ITMApplication` provides these hooks.

## Note
This package is designed to be used with the [@itwin/mobile-sdk-core](https://github.com/iTwin/mobile-sdk-core) and [@itwin/mobile-ui-react](https://github.com/iTwin/mobile-ui-react) packages. Those two packages are intended to be installed via npm, and their version number must match the version number of this package. Furthermore, they use __iTwin.js 5.14.3__, and your app must use that same version of iTwin.js.
