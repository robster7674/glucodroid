# GlucoDroid — Claude Code Instructions

App package: `cloud.glucodroid`

## Package and app name — NEVER change

The application package must always be `cloud.glucodroid` and the app label must always be `GlucoDroid`.
These are set in `Common/build.gradle`:
- `defaultConfig { applicationId "cloud.glucodroid" }`
- Every release build type must have `resValue "string", "app_name", "GlucoDroid"`

If an upstream merge changes either of these, revert immediately and do not ship until fixed.

## Git workflow

After completing any fix or feature on the `glucodroid` branch, always commit and push the changes immediately.
Use a clear commit message, then run `git push`.

## Build

After a successful build, copy the output APK to:

```
~/Downloads/glucodroid.apk
```

The file must always be named exactly `glucodroid.apk` — nothing else, ever.

Build command:

```
./gradlew assembleMobileLibre3SiDexGoogleReleaser -Pno_x86 -Pno_x86_64
```

The APK will be under `Common/build/outputs/apk/`.

If the build cache returns a stale APK (wrong package name or version), run `./gradlew clean` first.
