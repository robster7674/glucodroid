# JugglucoNG — Claude Code Instructions

## Build

After a successful build, copy the output APK to:

```
~/Downloads/JugglucoNG-R.apk
```

The file must always be named exactly `JugglucoNG-R.apk` — nothing else, ever.

Build command:

```
./gradlew assembleMobileLibre3SiDexGoogleRelease -Pno_x86 -Pno_x86_64
```

The APK will be under `Common/build/outputs/apk/`.
