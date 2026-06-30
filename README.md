# apk-fileupload

A tiny Android app that lets you pick a local file on your phone and upload it to a
**SMB / Windows file share** on your network — built **without Android Studio**, using only
the Android command-line SDK + Gradle. Designed to reach the file server over **Tailscale**.

## What the app does

- Pick any file with the system file picker (Storage Access Framework — no storage
  permission prompt needed).
- Enter your SMB server (a Tailscale hostname or `100.x.y.z` IP), share, optional subfolder,
  username and password. Settings are remembered between launches.
- Streams the file straight to the share over SMB2/3 (pure-Java [smbj], no native code),
  with a progress bar.

## Build the APK (no Android Studio)

The toolchain lives in `~/android-build` (OpenJDK 17 + Android cmdline-tools, build-tools
34.0.0, platform 34). To (re)build:

```bash
./build.sh
```

The debug-signed APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

That's an installable APK. (Debug builds are signed with the auto-generated debug key, which
is fine for personal sideloading.)

### Rebuilding the toolchain from scratch

If `~/android-build` is gone, re-run `~/android-build/install-toolchain.sh` (downloads JDK 17
and the Android cmdline-tools, installs `platform-tools`, `platforms;android-34`,
`build-tools;34.0.0`). No `sudo` and no Android Studio required.

## Install it on your phone

1. Copy `app-debug.apk` to the phone (USB, or from WSL it's under
   `\\wsl$\...\app\build\outputs\apk\debug\`).
2. On the phone, tap the APK and allow "install unknown apps" for your file manager/browser
   when prompted.
3. Open **File Upload**.

> Or, with USB debugging on: `~/android-build/sdk/platform-tools/adb install -r app-debug.apk`

## Using it over Tailscale

- Install **Tailscale** on the phone and sign in to the same tailnet as your file server.
- Make sure the file server is on the tailnet (running Tailscale itself, or reachable through
  a [subnet router]).
- In the app's **SMB server** field, use the server's Tailscale MagicDNS name (e.g. `nas`) or
  its `100.x.y.z` address — **not** its LAN IP, so traffic goes over the tailnet.
- **Share name** is the shared folder (e.g. `uploads`). **Remote subfolder** is optional and
  is created if it doesn't exist. For a workgroup/local account leave the domain off the
  username, or use `DOMAIN\user`.

## Project layout

```
app/src/main/java/com/example/fileupload/MainActivity.java   UI + SMB upload logic
app/src/main/res/layout/activity_main.xml                    the form
app/src/main/AndroidManifest.xml                             INTERNET permission only
app/build.gradle                                             deps: appcompat, smbj
build.sh                                                     env + ./gradlew assembleDebug
```

[smbj]: https://github.com/hierynomus/smbj
[subnet router]: https://tailscale.com/kb/1019/subnets
