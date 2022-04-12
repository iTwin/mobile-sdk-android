# Contributing

We welcome all types of contribution.

Need a feature or found a bug? Please create an [issue](https://github.com/iTwin/itwinjs-core/issues) and add the `android` label.

Have a question or suggestion? Please create a [discussion](https://github.com/iTwin/itwinjs-core/discussions) and add the `android` label.

Want to contribute by creating a pull request? Great! [Fork](https://docs.github.com/en/get-started/quickstart/fork-a-repo#forking-a-repository)  this repository to get started.

## Contribution guidelines

We'd love to accept your contributions, there are just a few guidelines you need to follow.

### Contributor License Agreement (CLA)

A [Contribution License Agreement with Bentley](https://gist.github.com/imodeljs-admin/9a071844d3a8d420092b5cf360e978ca) must be signed before your contributions will be accepted. Upon opening a pull request, you will be prompted to use [cla-assistant](https://cla-assistant.io/) for a one-time acceptance applicable for all Bentley projects.
You can read more about [Contributor License Agreements](https://en.wikipedia.org/wiki/Contributor_License_Agreement) on Wikipedia.

### Pull Requests

All submissions go through a review process. We use GitHub pull requests for this purpose.
Consult [GitHub Help](https://help.github.com/articles/about-pull-requests/) for more information on using pull requests.

## Local development setup

Follow these instructions to do local builds of the Mobile SDK. You will need [git](https://git-scm.com/) and [Android Studio](https://developer.android.com/studio) installed on your computer.

### mobile-native-android

In this step we need to publish the mobile-native-android AAR file to the local Maven repository so it can be used in the next step.

```sh
git clone https://github.com/iTwin/mobile-native-android
cd mobile-native-android
./gradlew --no-daemon assembleGitHub publishToMavenLocal
```
#### Notes
- These instructions are specific to MacOS (or Linux), on Windows use `gradle.bat` instead.
- The `assemble` task copies the AAR file using `wget` whereas the `assembleGitHub` task uses the `gh` CLI.
- The file is large and is downloaded from the Releases, so it can take a while depending on your internet speed.
- The `publishToMavenLocal` tasks publishes the AAR file to the local Maven repository (`~/.m2/repository`) so it can be used by the next step.
- If `gradlew` issues an error stating "Unable to locate a Java Runtime", you can try: `brew install --cask temurin`. Note that it will prompt you for a `sudo` password.
- The `--no-daemon` option shouldn't be necessary but seems to be required, due to an unknown incompatibility between the two `gradlew` copies (even though they're identical). Without this option the `gradlew` call in the next step will hang.
- If you end up with a daemon running, you can do: `./gradlew --stop`

### mobile-sdk-android

In this step we will build the Mobile SDK Android code and publish it to the local Maven repository.

```sh
git clone https://github.com/iTwin/mobile-sdk-android
cd mobile-sdk-android
echo sdk.dir=/Users/$USER/Libarary/Android/sdk > local.properties
./gradlew --no-daemon publishToMavenLocal
```

#### Notes 
- The `echo` command which creates the `local.properties` file only needs to be done once. The `sdk.dir` to specify the location of the Android SDK you wish to use. Alternatively, you can set `ANDROID_SDK` in your shell. On Windows, the `echo` command will need to be changed to reflect where Android Studio installs the SDK. In Android Studio the SDK location can be shown by going to: Preferences | Appearance & Behavior | System Settings | Android SDK | Android SDK Location

### Using the local Maven repository

To use the locally published Mobile SDK in your App do the following:
- Add `mavenLocal()` to the list of repositories in the `settings.gradle` file. Make sure it is before any other repositories. Look at `mobile-sdk-android/settings.gradle` to see an example.
- In your app's `build.gradle` file, add a dependency to `mobile-sdk-android`. Something like this should work:
`implementation 'com.github.itwin:mobile-sdk-android:0.9.12'`
