#!/usr/bin/env python3
"""Local Journal two-phase synthetic test. Refuses phones and AVDs other than the named dedicated test AVD."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

PACKAGE = "com.veymur.sensor"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "com.veymur.recorder.core.LocalDataValidationTest#committedPrefixAndUncommittedRollbackAfterExternalProcessDeath"
MARKER = f"/sdcard/Android/data/{PACKAGE}/files/local-data-death-ready.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", default="veymur-polar-diagnostic-api34")
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = shutil.which("adb") or (str(Path(sdk) / "platform-tools/adb") if sdk else None)
    if not adb or not args.serial.startswith("emulator-"):
        parser.error("Use an installed adb and an explicit emulator serial")
    prefix = [adb, "-s", args.serial]

    def run(*command, check=True, timeout=20):
        return subprocess.run(prefix + list(command), text=True, capture_output=True, check=check, timeout=timeout)

    if not args.avd.startswith("veymur-polar-diagnostic-"):
        parser.error("The dedicated AVD name must start with veymur-polar-diagnostic-")
    if run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        parser.error("The selected device is not an emulator")
    if run("emu", "avd", "name").stdout.splitlines()[0].strip() != args.avd:
        parser.error("The selected emulator is not the named dedicated AVD")
    run("shell", "rm", "-f", MARKER)
    prepare = subprocess.Popen(prefix + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
        "-e", "localCrashPhase", "prepare", RUNNER], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        deadline = time.monotonic() + 30
        while run("shell", "test", "-f", MARKER, check=False).returncode != 0:
            if prepare.poll() is not None:
                raise RuntimeError("Preparation failed: " + prepare.communicate()[0])
            if time.monotonic() >= deadline:
                raise RuntimeError("Preparation did not produce its committed-prefix marker")
            time.sleep(0.2)
        marker = json.loads(run("shell", "cat", MARKER).stdout)
        if not marker.get("prefix", "").startswith("local-validation-") or not marker.get("session_id"):
            raise RuntimeError("Unexpected synthetic fixture marker")
        run("shell", "am", "force-stop", PACKAGE)
        prepare.communicate(timeout=10)
        verified = run("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
            "-e", "localCrashPhase", "verify", RUNNER, timeout=45)
        print(verified.stdout)
        if not re.search(r"OK \(1 tests?\)", verified.stdout) or "FAILURES" in verified.stdout:
            raise RuntimeError("Native recovery assertions failed")
        print("Local Journal recovery verified: committed prefix retained; uncommitted transaction rolled back; interrupted aggregates reproduced.")
    finally:
        if prepare.poll() is None:
            run("shell", "am", "force-stop", PACKAGE, check=False)
            prepare.communicate(timeout=10)


if __name__ == "__main__":
    main()
