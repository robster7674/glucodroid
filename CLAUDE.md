# GlucoDroid — Claude Code Instructions

App package: `cloud.glucosedroid`

## Git workflow

After completing any fix or feature on the `glucodroid` branch, always commit and push the changes immediately.
Use a clear commit message, then run `git push`.

## Build

After a successful build, copy the output APK to:

```
~/Downloads/GlucoDroid-R.apk
```

The file must always be named exactly `GlucoDroid-R.apk` — nothing else, ever.

Build command:

```
./gradlew assembleMobileLibre3SiDexGoogleReleaser -Pno_x86 -Pno_x86_64
```

The APK will be under `Common/build/outputs/apk/`.
