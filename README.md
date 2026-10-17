# Merge Controller

Combines a left and a right Switch 1 Joy-Con into one virtual controller on Android. While merging, other apps get input only from the virtual controller. The original Joy-Cons send them nothing. Rumble that apps send to the virtual controller is passed on to the Joy-Cons.

Requires [Shizuku](https://shizuku.rikka.app/) running (adb or root) and a kernel with the `hid-nintendo` driver. The Joy-Cons must show up as "Nintendo Switch Left/Right Joy-Con".

## Use

1. Pair both Joy-Cons in Bluetooth settings.
2. Open Merge Controller, tap **Grant permission**, and allow it in Shizuku.
3. Tap **Start merging**. You can leave the app; merging keeps running until you tap **Stop merging**, reboot, or Shizuku stops.

The **Input test** section shows which device each event comes from. The **Nintendo button layout** switch picks how the face buttons map. Off maps them by position, which is Android's default for Joy-Cons. On maps them by the printed label.

**Appear as an Xbox controller** (on by default) makes the merged controller present itself exactly like an Xbox Series controller connected over Bluetooth LE: name "Xbox Wireless Controller", IDs `045e:0b13`, Xbox stick and trigger ranges, analog-only triggers, and Capture sent as Share (`KEY_RECORD`). Some games only accept controllers they recognize by name. Call of Duty: Mobile, for example, matches Unity's joystick names against a list ("Xbox Wireless Controller", "DualSense Wireless Controller", …). With this off, the controller is "Merged Joy-Con Controller". Changing the setting recreates the merged controller.

**Forward rumble to Joy-Cons** turns rumble on or off. **Test rumble** vibrates the merged controller through Android's vibrator API: the left Joy-Con, then the right, then both.

### Games that use the first controller

Some games, including GTA: San Andreas, give player 1 to the first controller in Android's device list. Android orders that list by device ID, which it assigns in connection order. So if the Joy-Cons connected before merging started, they come first and the game ignores the merged controller.

The app shows whether the merged controller is listed before or after the Joy-Cons. If it's after, tap **Reconnect Joy-Cons**, then press any button on each Joy-Con. The phone can't reconnect Joy-Cons itself; they only accept a reconnect started by a button press. The app disconnects them and recreates the merged controller, so it gets the lower ID. This also works while the game is running, because the game assigns a returning controller the first free slot.

## How it works

- `MergeUserService` runs as a Shizuku user service in a separate process with the shell user's privileges (`input` and `uhid` groups). It is a daemon, so it outlives the app's process. The app reattaches with `Shizuku.peekUserService`.
- The native engine (`app/src/main/cpp/merger.c`):
  - opens the Joy-Con evdev nodes and takes an exclusive `EVIOCGRAB`, so Android's InputReader stops getting their events;
  - creates a `/dev/uinput` gamepad, "Xbox Wireless Controller" or "Merged Joy-Con Controller" depending on the identity setting, and writes the combined state to it.
- It watches `/dev/input` with inotify, so a Joy-Con that disconnects and reconnects is grabbed again automatically. Buttons held at disconnect are released.
- If the service process dies, the kernel drops the grabs and removes the virtual device, so the Joy-Cons go back to normal.
- **Rumble:**
  - The virtual controller advertises `FF_RUMBLE`. Android exposes it as an input device with two vibrators (strong and weak), and the engine answers the effect uploads and play/stop events that Android's InputReader sends.
  - The kernel driver can't carry rumble to the Joy-Cons: this Pixel's `hid-nintendo` is built without force feedback, and `/dev/hidraw*` is system-only. Instead, `JoyConRumble` sends the Joy-Con rumble output report (`0x10`) through Android's Bluetooth HID host (`BluetoothHidHost.sendData`). That API is hidden, but the shell user holds the `BLUETOOTH_PRIVILEGED` permission it needs.
  - Following the Linux driver's convention, the strong motor drives the left Joy-Con and the weak motor the right, at the default 160/320 Hz bands. Packets are resent every 50 ms while rumble is active.
- To find its own device among Android's input devices, even next to a real Xbox controller with the same name and IDs, the app compares descriptors. Android's descriptor is the SHA-1 of `:vendor:product:` plus the device's unique ID. Real Bluetooth controllers have a unique ID (their address); the virtual one doesn't.
- The app stamps each build. If it finds a merge service still running code from an older install, it replaces the service and resumes merging.

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
- There is no motion (gyro/accelerometer) on the virtual controller yet; the IMU stays on the original devices.
- Rumble relies on hidden Bluetooth APIs, which may change in a future Android release. If they do, rumble stops working, but merging is unaffected.

## Build

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Debugging tools

`tools/build_tools.sh` builds two arm64 binaries:

- `joymerge` runs the engine directly from `adb shell` and prints any rumble requested on its virtual controller.
- `fakejoy` creates two fake Joy-Cons and plays a scripted input sequence.

```sh
tools/build_tools.sh
adb push tools/build/joymerge tools/build/fakejoy /data/local/tmp/
adb shell /data/local/tmp/joymerge                 # merge the real Joy-Cons until Ctrl-C
adb shell /data/local/tmp/joymerge --left-name "Test Left Joy-Con" --right-name "Test Right Joy-Con"
adb shell /data/local/tmp/fakejoy                  # in a second shell
```
