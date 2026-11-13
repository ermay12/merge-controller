#!/usr/bin/env python3
"""Host regression test for the merge engine's button mapping.

Reproduces the rotated face-button codes seen on some phone kernels
(A=304, X=305, Y=307, B=308 instead of mainline's A=305, B=304, X=307, Y=308)
with fakejoy, then asserts what the virtual controller reports.

Run as root (needs /dev/uinput and /dev/input access):
    sudo python3 tools/test_remap.py
"""
import glob
import os
import re
import select
import struct
import subprocess
import sys
import time

TOOLS = os.path.dirname(os.path.abspath(__file__))
JOYMERGE = os.path.join(TOOLS, "build", "joymerge")
FAKEJOY = os.path.join(TOOLS, "build", "fakejoy")
PHONE_FACE = "A=304,B=308,X=305,Y=307"
NAMES = ["--left-name", "Test Left Joy-Con", "--right-name", "Test Right Joy-Con"]
XBOX = "Xbox Wireless Controller"
GENERIC = "Merged Joy-Con Controller"
EVENT_SIZE = struct.calcsize("llHHi")

# fakejoy's raw presses, in script order: left taps, right taps, D-pad chord,
# held R+ZR, reconnect A.
RAW = (
    [(0, c) for c in (310, 312, 314, 317, 309, 544, 545, 546, 547, 311, 313)]
    + [(1, c) for c in (304, 308, 305, 307, 311, 313, 315, 316, 318, 310, 312)]
    + [(0, 544), (0, 546), (1, 311), (1, 313), (1, 304)]
)
# The three-cycle alone only fixes the face buttons under the Nintendo preset.
# The default (positional) preset also swaps 307<->308, so the left button
# needs an explicit 307->307 override to stay X.
REMAP_3CYCLE = "1,308,304,1,304,305,1,305,308"
REMAP = REMAP_3CYCLE + ",1,307,307"
KEY_RECORD = 167  # linux/input-event-codes.h, the Xbox Share button
# Left taps: L, Minus, L3, Capture. Right taps: faces, R, Plus, Home, R3.
# Then the script holds R (311) while disconnecting, and presses A after reconnect.
XBOX_HEAD = [310, 314, 317, KEY_RECORD]
XBOX_TAIL = [311, 315, 316, 318, 311]


def find_event(name):
    for p in glob.glob("/sys/class/input/event*/device/name"):
        with open(p) as f:
            if f.read().strip() == name:
                return "/dev/input/" + p.split("/")[4]
    return None


def wait_device(name, present, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if (find_event(name) is not None) == present:
            return True
        time.sleep(0.1)
    return False


def run_scenario(label, identity, jm_args, expected_downs):
    print(f"--- {label}", flush=True)
    if not wait_device(identity, False):
        sys.exit(f"FAIL: a {identity} device is already present")
    jm = subprocess.Popen(
        [JOYMERGE, "--seconds", "60"] + jm_args,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    fd = None
    fj = None
    try:
        if not wait_device(identity, True):
            jm.kill()
            sys.exit("FAIL: virtual device did not appear:\n" + jm.communicate(timeout=5)[0])
        fd = os.open(find_event(identity), os.O_RDONLY | os.O_NONBLOCK)
        fj = subprocess.Popen(
            [FAKEJOY, "--prefix", "Test", "--wait", "3", "--face", PHONE_FACE],
            stdout=subprocess.DEVNULL,
        )
        downs = []
        buf = b""
        while fj.poll() is None:
            r, _, _ = select.select([fd], [], [], 0.2)
            if not r:
                continue
            buf += os.read(fd, 64 * EVENT_SIZE)
            while len(buf) >= EVENT_SIZE:
                _, _, typ, code, val = struct.unpack_from("llHHi", buf)
                buf = buf[EVENT_SIZE:]
                if typ == 1 and val == 1:
                    downs.append(code)
        time.sleep(0.3)
        r, _, _ = select.select([fd], [], [], 0)
        if r:
            buf += os.read(fd, 64 * EVENT_SIZE)
        while len(buf) >= EVENT_SIZE:
            _, _, typ, code, val = struct.unpack_from("llHHi", buf)
            buf = buf[EVENT_SIZE:]
            if typ == 1 and val == 1:
                downs.append(code)
        fj.wait()
        jm.terminate()
        out = jm.communicate(timeout=5)[0]
        raw = [(int(a), int(b)) for a, b in re.findall(r"raw press side=(\d+) code=(\d+)", out)]
        if downs != expected_downs:
            print(f"  expected downs: {expected_downs}\n  actual downs:   {downs}")
            sys.exit(f"FAIL: {label}: wrong output codes")
        if raw != RAW:
            print(f"  expected raw: {RAW}\n  actual raw:   {raw}")
            sys.exit(f"FAIL: {label}: wrong raw press detection")
        print("  PASS", flush=True)
    finally:
        if fj is not None and fj.poll() is None:
            fj.kill()
        if jm.poll() is None:
            jm.kill()
        if fd is not None:
            os.close(fd)
        wait_device(identity, False)


def main():
    if os.geteuid() != 0:
        sys.exit("needs root for /dev/uinput and /dev/input: sudo python3 tools/test_remap.py")
    for tool in (JOYMERGE, FAKEJOY):
        if not os.path.exists(tool):
            sys.exit(f"missing {tool}; run tools/build_tools.sh --host first")

    # Xbox identity, no remap: reproduces the phone bug. The bottom button
    # (B label, code 308) reports X(307), top (X, 305) reports B(305), etc.
    run_scenario(
        "reproduce bug: xbox, rotated codes, no remap", XBOX,
        ["--xbox"] + NAMES,
        XBOX_HEAD + [304, 307, 305, 308] + XBOX_TAIL + [304],
    )
    # The fix: bottom->A(304), right->B(305), top->Y(308), left->X(307).
    run_scenario(
        "fix: xbox, rotated codes, three-cycle remap", XBOX,
        ["--xbox", "--remap", REMAP] + NAMES,
        XBOX_HEAD + [305, 304, 308, 307] + XBOX_TAIL + [305],
    )
    # Overrides must win over the Nintendo preset (which already keeps 307->307,
    # so the three-cycle suffices here).
    run_scenario(
        "override wins: xbox + nintendo layout + remap", XBOX,
        ["--xbox", "--nintendo", "--remap", REMAP_3CYCLE] + NAMES,
        XBOX_HEAD + [305, 304, 308, 307] + XBOX_TAIL + [305],
    )
    # dst -1 removes the mapping: the left button (Y label, 307) goes silent.
    run_scenario(
        "unmap: xbox, remap + 307 unmapped", XBOX,
        ["--xbox", "--remap", REMAP + ",1,307,-1"] + NAMES,
        XBOX_HEAD + [305, 304, 308] + XBOX_TAIL + [305],
    )
    # Generic identity: Capture->C(306), ZL/ZR also report as keys 312/313.
    run_scenario(
        "generic identity + remap", GENERIC,
        ["--remap", REMAP] + NAMES,
        [310, 312, 314, 317, 306] + [305, 304, 308, 307] + [311, 313, 315, 316, 318] + [311, 313] + [305],
    )
    print("ALL PASS")


if __name__ == "__main__":
    main()