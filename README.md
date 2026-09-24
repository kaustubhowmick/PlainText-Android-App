# PlainText

A minimal Android plain-text editor that recreates classic Windows Notepad. It edits real `.txt` files through the system file picker. It has no notes database, no account, no internet permission, and no tracking.

- Design: [design.md](design.md)
- Where the implementation interprets or departs from the design: [DECISIONS.md](DECISIONS.md)

## Install a build from GitHub Actions

1. Open the repository's **Actions** tab and click the latest successful **Build** run.
2. Under **Artifacts**, download **PlainText-release-apk** (small, minified) or **PlainText-debug-apk**. GitHub delivers each artifact as a `.zip`; unzip it to get the `.apk`.
3. Copy the `.apk` to your phone and open it. Android asks you to allow installs from that app (for example Files or Chrome) the first time.

The two builds install side by side: the debug build's package name ends in `.debug`. Without release-signing secrets, the release APK is signed with the CI debug key (see DECISIONS.md), which is fine for testing but not for the Play Store.

Or with a cable: `adb install -r app-release.apk`.

## Build locally

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # R8-minified, app/build/outputs/apk/release/
scripts/check-apk-size.sh        # enforces the 2 MB limit, warns above 500 KB
```

To sign a release with your own key, set `PT_KEYSTORE`, `PT_KEYSTORE_PASSWORD`, `PT_KEY_ALIAS`, and `PT_KEY_PASSWORD`.
