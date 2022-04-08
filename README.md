# iTwin mobile-sdk-android

Copyright © Bentley Systems, Incorporated. All rights reserved. See [LICENSE.md](./LICENSE.md) for license terms and full copyright notice.

## Warning

This is pre-release software and provided as-is.

## About This Repository

This repository contains the Kotlin code used to build [iTwin.js](http://www.itwinjs.org) applications on Android devices.

## Note
This package is designed to be used with the [@itwin/mobile-sdk-core](https://github.com/iTwin/mobile-sdk-core) and [@itwin/mobile-ui-react](https://github.com/iTwin/mobile-ui-react) packages. Those two packages are intended to be installed via npm, and their version number must match the version number of this package. Furthermore, they use __iTwin.js 3.0.0__, and your app must use that same version of iModel.js.

## Using JitPack

To use the Mobile SDK in your App do the following:
- Add `maven { url 'https://jitpack.io' }` to the list of repositories in the `settings.gradle` file. Look at `mobile-sdk-android/settings.gradle` to see an example.
- In your app's `build.gradle` file, you'll need to add a dependency to `mobile-sdk-android`. Something like this should work:
`implementation 'com.github.itwin:mobile-sdk-android:0.9.12'`

## Local development

If you need to do local builds of the Mobile SDK instead of using JitPack, follow these instructions.

### mobile-native-android

This is simply a publishing step, not actually compiling any code.

```sh
git clone https://github.com/iTwin/mobile-native-android
cd mobile-native-android
./gradlew --no-daemon assembleGitHub publishToMavenLocal
```
#### Notes
- The `assemble` task copies the AAR file using `wget` whereas the `assembleGitHub` task uses the `gh` CLI.
- The file is large and is downloaded from the Releases, so it takes a little while.
- The `publishToMavenLocal` tasks publishes the AAR file to the local Maven repository (`~/.m2/repository`) so it can be used by the next step.
- If `gradlew` issues an error stating "Unable to locate a Java Runtime", you can try: `brew install --cask temurin`. Note that it will prompt you for a `sudo` password.

### mobile-sdk-android

In this step we will build the Mobile SDK Android code and publish it to the local Maven repository.

```sh
git clone https://github.com/iTwin/mobile-sdk-android
cd mobile-sdk-android
echo sdk.dir=/Users/$USER/Libarary/Android/sdk > local.properties
./gradlew --no-daemon publishToMavenLocal
```

#### Notes 
- Creation of the `local.properties` file only needs to be done once and needs to point to the Android SDK you wish to use. As an alternative, you can set `ANDROID_SDK` in your shell.
- The `--no-daemon` option shouldn't be necessary but is required. There seems to be an incompatibility between the two `gradlew` copies (even though they're identical). Without this option the 2nd `gradlew` call will hang.
- If you end up with a daemon running, you can do: `./gradlew --stop`

### Using the local Maven repository

To use the locally published Mobile SDK in your App do the following:
- Add `mavenLocal()` to the list of repositories in the `settings.gradle` file. Make sure it is before any other repositories. Look at `mobile-sdk-android/settings.gradle` to see an example.
- As shown above, in your app's `build.gradle` file, you'll need to add a dependency to `mobile-sdk-android`. Something like this should work:
`implementation 'com.github.itwin:mobile-sdk-android:0.9.12'`
