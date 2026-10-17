# Building Merge Controller

This page is for developers. If you just want to use the app, see the [README](README.md).

## Requirements

- JDK 17 or newer
- Android SDK with platform 36, NDK `27.1.12297006` and CMake `3.22.1`. Android Studio (or Gradle, once you've accepted the SDK licenses) installs the NDK and CMake automatically.
- An arm64 Android 12+ device, or an arm64/x86_64 emulator, for testing

## Build and install

```sh
git clone https://github.com/ermay12/merge-controller.git
cd merge-controller
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio and run the `app` configuration.

A debug build is signed with your local debug key. If the release from GitHub is installed, uninstall it before installing your own build, because Android won't replace an app signed with a different key.

After every install, the app replaces a merge service still running the old code, and resumes merging if it was on.

## Release builds

Release builds are signed with the key described by a properties file, which you point to with the `mergeController.signing` Gradle property:

```properties
# e.g. ~/.android/merge-controller-release.properties (keep it out of the repo)
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```sh
./gradlew assembleRelease -PmergeController.signing="$HOME/.android/merge-controller-release.properties"
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Without `mergeController.signing`, the release build is signed with the debug key, so it can still be sideloaded.

Minification is off on purpose. The service reaches hidden Bluetooth APIs through reflection, and Shizuku instantiates the user service by class name.

## Logs

```sh
adb logcat -s MergeController MergeUserService JoyMerge JoyConRumble ShellHidHost
```

`JoyMerge` (the native engine) logs grabs, releases and errors. In debug builds, `MergeController` (the app) also logs every button press and stick movement the input test sees.

## Debugging tools

`tools/build_tools.sh` builds two arm64 command-line tools that run from `adb shell` as the shell user, without the app or Shizuku:

- `joymerge` runs the merge engine directly and prints any rumble requested on its virtual controller.
- `fakejoy` creates two fake Joy-Cons and plays a scripted input sequence, for testing without hardware.

```sh
tools/build_tools.sh
adb push tools/build/joymerge tools/build/fakejoy /data/local/tmp/
adb shell /data/local/tmp/joymerge                 # merge the real Joy-Cons until Ctrl-C
adb shell '/data/local/tmp/joymerge --left-name "Test Left Joy-Con" --right-name "Test Right Joy-Con"'
adb shell /data/local/tmp/fakejoy                  # in a second shell
```

Quote the whole command when an argument contains spaces, because `adb shell` splits it again on the device. `joymerge` also takes `--nintendo` (Nintendo button layout), `--xbox` (Xbox identity) and `--seconds N`.

To test the app itself without Joy-Cons (on an emulator, for example), give the fake Joy-Cons the real names and keep them around for a while:

```sh
adb shell '/data/local/tmp/fakejoy --prefix "Nintendo Switch" --wait 600'
```

## How it works

```
Joy-Con (L) ──┐
              ├─► merge engine (merger.c) ──► virtual gamepad (uinput) ──► games
Joy-Con (R) ──┘   grabs both evdev nodes             │
     ▲                                               │ rumble requests
     └───── Bluetooth HID rumble reports ◄───────────┘
            (JoyConRumble.kt)
```

- `MergeUserService` runs as a Shizuku user service in a separate process with the shell user's privileges (`input` and `uhid` groups). It's a daemon, so it outlives the app's process. The app reattaches with `Shizuku.peekUserService`.
- The native engine (`app/src/main/cpp/merger.c`):
  - opens the Joy-Con evdev nodes and takes an exclusive `EVIOCGRAB`, so Android's InputReader stops getting their events;
  - creates a `/dev/uinput` gamepad, "Xbox Wireless Controller" or "Merged Joy-Con Controller" depending on the identity setting, and writes the combined state to it.
- It watches `/dev/input` with inotify, so a Joy-Con that disconnects and reconnects is grabbed again automatically. Buttons held at disconnect are released.
- If the service process dies, the kernel drops the grabs and removes the virtual device, so the Joy-Cons go back to normal.
- **Rumble:**
  - The virtual controller advertises `FF_RUMBLE`. Android exposes it as an input device with two vibrators (strong and weak), and the engine answers the effect uploads and play/stop events that Android's InputReader sends.
  - The kernel driver often can't carry rumble to the Joy-Cons: many Android kernels (the Pixel's, for one) build `hid-nintendo` without force feedback, and `/dev/hidraw*` is system-only. Instead, `JoyConRumble` sends the Joy-Con rumble output report (`0x10`) through Android's Bluetooth HID host (`BluetoothHidHost.sendData`). That API is hidden, but the shell user holds the `BLUETOOTH_PRIVILEGED` permission it needs. `ShellHidHost` creates a `BluetoothAdapter` that identifies as `com.android.shell`, through the hidden `createAdapter(Context)` on newer Bluetooth modules (Android 17) or `createAdapter(AttributionSource)` on older ones.
  - Following the Linux driver's convention, the strong motor drives the left Joy-Con and the weak motor the right, at the default 160/320 Hz bands. Packets are resent every 50 ms while rumble is active.
- To find its own device among Android's input devices, even next to a real Xbox controller with the same name and IDs, the app compares descriptors. Android's descriptor is the SHA-1 of `:vendor:product:` plus the device's unique ID. Real Bluetooth controllers have a unique ID (their address); the virtual one doesn't.
- The app stamps each build. If it finds a merge service still running code from an older install, it replaces the service and resumes merging.

### Output mapping

Both identities use IDs with no device-specific key layout on Android, so Android maps them with `Generic.kl`:

| Joy-Con | Android |
|---|---|
| Left stick / Right stick | `AXIS_X`/`AXIS_Y` / `AXIS_Z`/`AXIS_RZ` |
| D-pad | `AXIS_HAT_X`/`AXIS_HAT_Y` |
| L / R | `BUTTON_L1` / `BUTTON_R1` |
| ZL / ZR | `AXIS_LTRIGGER` / `AXIS_RTRIGGER`, plus `BUTTON_L2` / `BUTTON_R2` with the generic identity |
| − / + / Home | `BUTTON_SELECT` / `BUTTON_START` / `BUTTON_MODE` |
| Capture | `BUTTON_C`, or `MEDIA_RECORD` (the Xbox Share button) with the Xbox identity |
| Stick clicks | `BUTTON_THUMBL` / `BUTTON_THUMBR` |
| B, A, Y, X | `BUTTON_A`, `B`, `X`, `Y` by position; with the Nintendo layout, A→`A`, B→`B`, X→`X`, Y→`Y` |

SL/SR are not mapped.

With the Xbox identity, the controller copies an Xbox Series controller connected over Bluetooth LE: name "Xbox Wireless Controller", IDs `045e:0b13`, Xbox stick and trigger ranges, and analog-only triggers.

## Project layout

| Path | What's there |
|---|---|
| `app/src/main/java/…/MainActivity.kt` | The app's single screen |
| `app/src/main/java/…/MergeServiceClient.kt` | Shizuku state, binding to the merge service |
| `app/src/main/java/…/MergeUserService.kt` | The Shizuku user service (runs as shell) |
| `app/src/main/java/…/JoyConRumble.kt`, `ShellHidHost.kt` | Rumble forwarding over Bluetooth HID |
| `app/src/main/aidl/…/IMergeService.aidl` | App ↔ service interface |
| `app/src/main/cpp/merger.c` | The native merge engine |
| `tools/` | `joymerge` and `fakejoy` debugging tools |

## Contributing

Issues and pull requests are welcome. Please test on a real device with real Joy-Cons if your change touches the engine, and describe your device and Android version in the PR.

By contributing, you agree that your contributions are licensed under the project's [GPL-3.0 license](LICENSE).
