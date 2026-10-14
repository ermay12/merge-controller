# Merge Controller

Combines a left and a right Switch 1 Joy-Con into one virtual controller on Android. While merging, other apps get input only from the virtual controller. The original Joy-Cons send them nothing.

Requires [Shizuku](https://shizuku.rikka.app/) running (adb or root) and a kernel with the `hid-nintendo` driver. The Joy-Cons must show up as "Nintendo Switch Left/Right Joy-Con".

## Use

1. Pair both Joy-Cons in Bluetooth settings.
2. Open Merge Controller, tap **Grant permission**, and allow it in Shizuku.
3. Tap **Start merging**. You can leave the app; merging keeps running until you tap **Stop merging**, reboot, or Shizuku stops.

The **Input test** section shows which device each event comes from. The **Nintendo button layout** switch picks how the face buttons map. Off maps them by position, which is Android's default for Joy-Cons. On maps them by the printed label.

## How it works

- `MergeUserService` runs as a Shizuku user service in a separate process with the shell user's privileges (`input` and `uhid` groups). It is a daemon, so it outlives the app's process. The app reattaches with `Shizuku.peekUserService`.
- The native engine (`app/src/main/cpp/merger.c`):
  - opens the Joy-Con evdev nodes and takes an exclusive `EVIOCGRAB`, so Android's InputReader stops getting their events;
  - creates a `/dev/uinput` gamepad named "Merged Joy-Con Controller" and writes the combined state to it.
- It watches `/dev/input` with inotify, so a Joy-Con that disconnects and reconnects is grabbed again automatically. Buttons held at disconnect are released.
- If the service process dies, the kernel drops the grabs and removes the virtual device, so the Joy-Cons go back to normal.

Output mapping (Android `Generic.kl`):

| Joy-Con | Android |
|---|---|
| Left stick / Right stick | `AXIS_X`/`AXIS_Y` / `AXIS_Z`/`AXIS_RZ` |
| D-pad | `AXIS_HAT_X`/`AXIS_HAT_Y` |
| L / R | `BUTTON_L1` / `BUTTON_R1` |
| ZL / ZR | `BUTTON_L2` / `BUTTON_R2` and `AXIS_LTRIGGER` / `AXIS_RTRIGGER` |
| − / + / Home / Capture | `BUTTON_SELECT` / `BUTTON_START` / `BUTTON_MODE` / `BUTTON_C` |
| Stick clicks | `BUTTON_THUMBL` / `BUTTON_THUMBR` |
| B, A, Y, X | `BUTTON_A`, `B`, `X`, `Y` by position; with the Nintendo layout, A→`A`, B→`B`, X→`X`, Y→`Y` |

SL/SR are not mapped.

## Limitations

- Without root, the original Joy-Cons stay in Android's device list (`InputManager.getInputDeviceIds()`), but send no input. A game that binds player 1 to the first listed controller, rather than the first one that sends input, may pick a silent Joy-Con. Fixing that would need root, to unbind the driver.
- There is no rumble or motion on the virtual controller. This kernel's Joy-Con nodes expose no force feedback, and the IMU stays on the original devices.

## Build

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Debugging tools

`tools/build_tools.sh` builds two arm64 binaries:

- `joymerge` runs the engine directly from `adb shell`.
- `fakejoy` creates two fake Joy-Cons and plays a scripted input sequence.

```sh
tools/build_tools.sh
adb push tools/build/joymerge tools/build/fakejoy /data/local/tmp/
adb shell /data/local/tmp/joymerge                 # merge the real Joy-Cons until Ctrl-C
adb shell /data/local/tmp/joymerge --left-name "Test Left Joy-Con" --right-name "Test Right Joy-Con"
adb shell /data/local/tmp/fakejoy                  # in a second shell
```
