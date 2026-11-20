#!/usr/bin/env python3
"""Opt-in, read-only LOCAL inspection of the owner's installed Elsewhere base APK.

Runs only when an owner supplies an explicit ADB device serial. No GitHub
runner can supply an owner's real installed app; do not put this in CI or send
the extracted APK elsewhere. No install, uninstall, root, app-data access,
signing, credential access, or Android backup operation is performed.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

from verify_apk_identity import (
    GateFailure, PRODUCTION_PACKAGE, apk_tool_identity, check_native_libraries,
    verify_local_upgrade_pair, verify_policy,
)


class LocalInspectionError(ValueError):
    pass


def is_installed_base_path(path: str) -> bool:
    """Accept one installed package base.apk, never a private database or cache."""
    return (len(path) <= 2048
            and path.endswith("/base.apk")
            and (path.startswith("/data/app/") or path.startswith("/mnt/expand/"))
            and PRODUCTION_PACKAGE in path
            and ".." not in path.split("/")
            and all(32 <= ord(character) < 127 for character in path))


def run_adb(*args: str, timeout: int = 40) -> str:
    """Command allowlist: devices, two read-only shell queries, one base.apk pull."""
    allowed = args == ("devices",)
    if len(args) >= 4 and args[0] == "-s":
        allowed = allowed or args[2:] in (
            ("shell", "getprop", "ro.kernel.qemu"),
            ("shell", "pm", "path", PRODUCTION_PACKAGE),
        )
        if len(args) == 5 and args[2] == "pull":
            allowed = allowed or (is_installed_base_path(args[3])
                                  and Path(args[4]).name == "installed-base.apk")
    if not allowed:
        raise LocalInspectionError("ADB command is not allowed by read-only verifier")
    result = subprocess.run(("adb", *args), capture_output=True, text=True,
                            check=False, timeout=timeout)
    if result.returncode:
        raise LocalInspectionError("ADB read-only operation failed (no installation attempted)")
    return result.stdout


def validate_serial(serial: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{4,128}", serial):
        raise LocalInspectionError("A valid explicit ADB device serial is required")
    if serial.startswith("emulator-"):
        raise LocalInspectionError("Refusing to treat an emulator as the owner's installed app")


def check_selected_device(serial: str) -> None:
    validate_serial(serial)
    listing = run_adb("devices").splitlines()[1:]
    ready = [parts[0] for line in listing
             if len(parts := line.split()) >= 2 and parts[1] == "device"]
    if ready.count(serial) != 1:
        raise LocalInspectionError(
            "Selected device is not uniquely connected and authorized in adb devices"
        )
    if run_adb("-s", serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1":
        raise LocalInspectionError("Cannot establish installed production baseline on an emulator")


def installed_base_path(serial: str) -> str:
    listing = run_adb("-s", serial, "shell", "pm", "path", PRODUCTION_PACKAGE)
    candidates = []
    for line in listing.splitlines():
        if not line.startswith("package:"):
            continue
        path = line[len("package:"):].strip()
        if is_installed_base_path(path):
            candidates.append(path)
    if len(candidates) != 1:
        raise LocalInspectionError("Expected exactly one installed Elsewhere base APK path")
    return candidates[0]


def inspect_installed(serial: str, aapt: str, apksigner: str,
                      candidate: Path | None = None):
    """Read only; temporary installed APK deleted on success or failure."""
    if os.environ.get("GITHUB_ACTIONS", "").lower() == "true":
        raise LocalInspectionError(
            "Owner-device inspection is disabled in GitHub Actions"
        )
    check_selected_device(serial)
    source = installed_base_path(serial)
    if candidate is not None and (not candidate.is_file() or candidate.stat().st_size == 0):
        raise LocalInspectionError("Finished release candidate APK is missing or empty")

    # TemporaryDirectory is private (0700), and its sole copied APK is
    # deleted by the context manager even if parsing or the gate fails.
    with tempfile.TemporaryDirectory(prefix="elsewhere-local-identity-") as directory:
        local_apk = Path(directory) / "installed-base.apk"
        run_adb("-s", serial, "pull", source, str(local_apk), timeout=180)
        if not local_apk.is_file() or local_apk.stat().st_size == 0:
            raise LocalInspectionError("ADB did not produce a valid local base APK copy")
        installed = apk_tool_identity(local_apk, aapt, apksigner)
        verify_policy(installed, "installed-baseline")
        if candidate is not None:
            updated = apk_tool_identity(candidate, aapt, apksigner)
            verify_local_upgrade_pair(installed, updated)
            check_native_libraries(candidate)
        return installed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True,
                        help="Exact owner-authorized physical device from: adb devices")
    parser.add_argument("--aapt", required=True, help="Local Android SDK aapt or aapt.exe path")
    parser.add_argument("--apksigner", required=True,
                        help="Local Android SDK apksigner or apksigner.bat path")
    parser.add_argument("--candidate", type=Path, default=None,
                        help="Optional finished production-signed release APK (local file only)")
    args = parser.parse_args()
    try:
        verified = inspect_installed(args.serial, args.aapt, args.apksigner,
                                     args.candidate)
    except (LocalInspectionError, GateFailure, OSError, subprocess.TimeoutExpired) as failure:
        # Fail closed without printing the owner's local path or file contents.
        detail = failure.code if isinstance(failure, GateFailure) else type(failure).__name__
        print(f"INSTALLED_BASELINE_NOT_VERIFIED reason={detail}; no install or data changes")
        return 1

    print("INSTALLED_ELSEWHERE_V15_IDENTITY_PASS "
          f"package={verified.package} versionCode={verified.version_code} "
          f"versionName={verified.version_name} "
          f"signerSha256={verified.signer_sha256}")
    if args.candidate:
        print("LOCAL_UPGRADE_APK_PAIR_PASS: both actual APK identities and candidate packaging matched")
    print("READ_ONLY_ONLY: copied base.apk to a private temporary directory and deleted it; "
          "no installation, restore, release signing, or app data access. "
          "Passing identity checks does not approve a production upgrade.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
