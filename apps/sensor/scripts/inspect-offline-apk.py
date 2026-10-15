#!/usr/bin/env python3
"""Inspect a diagnostic offline APK; needs Python 3 and the pinned Android build tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile
from xml.etree import ElementTree


def inspect(apk: Path) -> dict:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise ValueError("Set ANDROID_HOME or ANDROID_SDK_ROOT")
    tools = Path(sdk) / "build-tools" / "36.0.0"
    def run(tool: str, *arguments: str) -> str:
        return subprocess.check_output([str(tools / tool), *arguments], text=True)
    manifest = run("aapt2", "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml")
    badging = run("aapt2", "dump", "badging", str(apk))
    signature = run("apksigner", "verify", "--print-certs", str(apk))
    core = re.search(r'CORE_VERSION[^\n]*\n[^\n]*:value[^=]*="([^"]+)"', manifest)
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: (\S+)", signature)
    if "package: name='com.veymur.sensor'" not in badging:
        raise ValueError("Unexpected applicationId")
    if not re.search(r"^(?:minSdkVersion|sdkVersion):'33'$", badging, re.MULTILINE) or "targetSdkVersion:'36'" not in badging:
        raise ValueError("Unexpected min/target SDK")
    if "application-debuggable" in badging or "debuggable(0x0101000f)=true" in manifest:
        raise ValueError("Expected a non-debuggable release variant")
    source_manifest = Path(__file__).resolve().parents[1] / "modules/veymur-recorder/android/src/main/AndroidManifest.xml"
    android = "{http://schemas.android.com/apk/res/android}"
    expected_core = next((item.get(android + "value") for item in ElementTree.parse(source_manifest).iter("meta-data")
                          if item.get(android + "name") == "com.veymur.recorder.CORE_VERSION"), None)
    if not expected_core or not core or core.group(1) != expected_core:
        raise ValueError("Unexpected native core identity")
    if "com.veymur.recorder.core.RecorderService" not in manifest or "com.veymur.sensor.exports" not in manifest:
        raise ValueError("Recorder service/provider missing")
    if not certificate:
        raise ValueError("Missing verified signer identity")
    with ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP checksum failure")
        bundle = archive.read("assets/index.android.bundle")
        if not bundle:
            raise ValueError("Embedded JS bundle is empty")
        abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
        if abis != ["arm64-v8a", "x86_64"]:
            raise ValueError(f"Unexpected ABIs: {abis}")
    return {
        "apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "applicationId": "com.veymur.sensor", "coreVersion": core.group(1),
        "minSdk": 33, "targetSdk": 36, "debuggable": False, "abis": abis,
        "bundleBytes": len(bundle), "bundleSha256": hashlib.sha256(bundle).hexdigest(),
        "signerCertificateSha256": certificate.group(1),
        "scope": "Diagnostic APK inspection; runtime and sensor validation are separate",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.apk), indent=2))
