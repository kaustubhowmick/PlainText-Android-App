# Implementation decisions

`design.md` is the source of truth. This file records every place where the implementation had to interpret it, resolve a conflict, or deviate from it, and why. The tie-breaker is always the design's philosophy: real `.txt` files, no internal notes store, and the smallest possible APK.

## Build and tooling

- **Plugin versions.** AGP 8.13.0 with Kotlin 2.2.20 and Gradle 8.14.3, exactly as §11.3 pins them. Newer Kotlin releases exist, but this combination is known to work together; bump both together later.
- **Release signing.** §11.5 reads the release key from `PT_KEYSTORE*` environment variables. When they are absent (local builds, forks, the default GitHub Actions run) the release build is signed with the debug key instead of producing an uninstallable unsigned APK. The CI workflow caches the debug key so successive CI builds can be installed over each other. For a Play Store release, set the four `PT_*` secrets.
- **Instrumented-test dependencies.** §2.3 lists AndroidX test, Espresso, and UI Automator for `androidTest`. The requested scope for this build is JVM unit tests only, so those dependencies are not declared yet; they are test-only and would not affect the APK when added.
- **CI lint job.** §10.4 runs lint on every PR. Lint runs as a separate job so a lint finding never prevents the APK artifacts from being produced.
