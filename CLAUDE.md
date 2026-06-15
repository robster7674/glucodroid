# GlucoDroid — Claude Code Instructions

App package: `cloud.glucodroid`

## Package and app name — NEVER change

The application package must always be `cloud.glucodroid` and the app label must always be `GlucoDroid`.
These are set in `Common/build.gradle`:
- `defaultConfig { applicationId "cloud.glucodroid" }`
- Every release build type must have `resValue "string", "app_name", "GlucoDroid"`

If an upstream merge changes either of these, revert immediately and do not ship until fixed.

## Git workflow

Never push directly to `origin/glucodroid`. All fixes and features go through a PR:

1. Create a feature branch off `glucodroid` (e.g. `fix/<short-name>` or `feat/<short-name>`).
2. Commit the changes on that branch.
3. Push the feature branch: `git push -u origin <branch>`.
4. Open a PR with `gh pr create --base glucodroid --head <branch> --title "..." --body "..."`.
5. Merge with `gh pr merge <n> --squash --delete-branch` once CI is green.
6. The `glucodroid` branch itself is updated by the squash merge — never `git push` to it directly.

`origin` is `robster7674/glucodroid` (this repo). `upstream` is `ctqvva/JugglucoNG` — **never push to upstream** under any circumstances; it is read-only mirror reference.

## Release process

Every release follows these steps in order — do not skip any:

1. Bump `versionName` / `versionCode` in `Common/build.gradle` defaultConfig.
2. Build: `./gradlew assembleMobileLibre3SiDexNogoogleRelease -Pno_x86 -Pno_x86_64`.
3. Copy APK to `~/Downloads/glucodroid.apk` (exact filename — never rename).
4. Commit the version bump + any other release-blocker fixes on the feature branch.
5. Open and merge the PR into `glucodroid` (squash, delete branch).
6. Tag the merged commit on `glucodroid`: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
7. Create the GitHub release with `gh release create vX.Y.Z --prerelease --title "vX.Y.Z" --notes-file <notes.md> --target glucodroid`. (If `gh release create` fails on scope, use the REST API with `gh auth token`.)
8. **Upload the APK as a release asset** — `~/Downloads/glucodroid.apk` MUST be attached to the release as `glucodroid.apk`. A release with notes but no APK is incomplete and the release is not done. Verify `browser_download_url` is present in the upload response.

## Fresh clone setup

After cloning, initialise the libjuice native submodule before building:

```
git submodule update --init
echo "sdk.dir=/home/rob/android-sdk" > local.properties
```

## Build

After a successful build, copy the output APK to:

```
~/Downloads/glucodroid.apk
```

The file must always be named exactly `glucodroid.apk` — nothing else, ever.

Build command:

```
./gradlew assembleMobileLibre3SiDexNogoogleRelease -Pno_x86 -Pno_x86_64
```

The APK will be under `Common/build/outputs/apk/`.

Use the `nogoogle` flavour (not `google`): it sets minSdk 23 (raised from 21
in 1.0.0-Alpha due to work-runtime-ktx dependency), omits the
`requireWatch` manifest flag, and is the correct variant for F-Droid and
other non-Play-Store distribution channels.

Use the `release` build type (not `releaser`): `releaser` only differs by
appending "R" to the version name and passing a no-op `-DAPPSUFFIX` flag —
`release` is the clean production build.

If the build cache returns a stale APK (wrong package name or version), run `./gradlew clean` first.
