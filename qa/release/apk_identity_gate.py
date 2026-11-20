#!/usr/bin/env python3
"""Fail-closed offline inspection of an APK's actual package and signing identity.

This never installs an APK, opens any keystore or reads user data. The frozen
v1.5 signer pin comes from the historical signed-production workflow:
.github/workflows/build-v15-release-apk.yml at the 1.5.2 production candidate.
A production *upgrade* requires a new monotonically larger versionCode.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

PRODUCTION_PACKAGE = "com.aistudio.elsewhere.abwzy.v15"
SANDBOX_PACKAGE = "com.aistudio.elsewhere.abwzy.test"
WORLDS_PREVIEW_PACKAGE = "com.aistudio.elsewhere.abwzy.v15.test.worlds"
V15_INSTALLED_VERSION_CODE = 3
V15_INSTALLED_VERSION_NAME = "1.5.2"
# Certified v1.5 / v1.5.1 production signer (SHA-256 certificate digest).
PRODUCTION_CERT_SHA256 = (
    "7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5"
)


class IdentityFailure(ValueError):
    """Explicit, non-sensitive validation failure for release automation."""


@dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str
    signer_sha256: str
    debuggable: bool


def parse_apk_tool_outputs(badging: str, signer_report: str) -> ApkIdentity:
    match = re.search(
        r"^package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']*)'",
        badging,
        re.MULTILINE,
    )
    if match is None:
        raise IdentityFailure("BADGING_MISSING: cannot identify installed package/version")

    fingerprints = re.findall(
        r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F: ]+)\s*$",
        signer_report,
        re.MULTILINE,
    )
    if len(fingerprints) != 1 or fingerprints[0][0] != "1":
        raise IdentityFailure("SIGNER_COUNT_INVALID: require exactly one verified signing certificate")
    signer = fingerprints[0][1].replace(":", "").replace(" ", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", signer):
        raise IdentityFailure("SIGNER_DIGEST_INVALID: expected full SHA-256 certificate fingerprint")
    return ApkIdentity(
        package=match.group(1),
        version_code=int(match.group(2)),
        version_name=match.group(3),
        signer_sha256=signer,
        debuggable=bool(re.search(r"^application-debuggable\b", badging, re.MULTILINE)),
    )


def check_identity(identity: ApkIdentity, mode: str, qa_signer: str | None = None) -> None:
    expected = {
        "production": PRODUCTION_PACKAGE,
        "qa-release": PRODUCTION_PACKAGE,
        "debug": PRODUCTION_PACKAGE,
        "sandbox": SANDBOX_PACKAGE,
        "worlds-preview": WORLDS_PREVIEW_PACKAGE,
    }
    if mode not in expected:
        raise IdentityFailure("INVALID_GATE_MODE")
    if identity.package != expected[mode]:
        raise IdentityFailure("PACKAGE_MISMATCH: APK package is not the expected build variant")
    if mode in ("production", "qa-release") and identity.debuggable:
        raise IdentityFailure("RELEASE_DEBUGGABLE: release must never enable Android debugging")

    signer = identity.signer_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", signer):
        raise IdentityFailure("SIGNER_DIGEST_INVALID")

    if mode == "production":
        if signer != PRODUCTION_CERT_SHA256:
            raise IdentityFailure("SIGNER_MISMATCH: refusing an APK signed by a non-production certificate")
        if identity.version_code <= V15_INSTALLED_VERSION_CODE:
            raise IdentityFailure("VERSION_NOT_UPGRADED: versionCode must exceed the installed v15 baseline")
        if identity.version_name == V15_INSTALLED_VERSION_NAME:
            raise IdentityFailure("VERSION_NAME_STALE: candidate still claims to be the installed release")
    elif mode == "qa-release":
        if qa_signer is None or not re.fullmatch(r"[0-9a-fA-F]{64}", qa_signer):
            raise IdentityFailure("QA_SIGNER_PIN_REQUIRED")
        if qa_signer.lower() == PRODUCTION_CERT_SHA256:
            raise IdentityFailure("QA_SIGNER_COLLIDES_WITH_PRODUCTION")
        if signer != qa_signer.lower():
            raise IdentityFailure("QA_SIGNER_MISMATCH")
        # It is fine for a disposable QA APK to contain current versionCode.
        # It is NEVER a production-upgrade-approved result.
    else:
        # Debug currently shares production's application ID, but its
        # certificate MUST NOT permit an upgrade over the production install.
        if signer == PRODUCTION_CERT_SHA256:
            raise IdentityFailure("TEST_SIGNER_IS_PRODUCTION: never sign test variants with production key")


def inspect_apk(apk: Path, aapt: Path, apksigner: Path) -> ApkIdentity:
    if not apk.is_file():
        raise IdentityFailure("APK_MISSING")
    commands = [
        [str(aapt), "dump", "badging", str(apk)],
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
    ]
    reports = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            # Never dump arbitrary tool logs or signature details into public CI.
            raise IdentityFailure("APK_TOOL_FAILED: aapt/apksigner did not accept the APK")
        reports.append(result.stdout)
    return parse_apk_tool_outputs(*reports)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument(
        "--mode",
        choices=["production", "qa-release", "debug", "sandbox", "worlds-preview"],
        required=True,
    )
    parser.add_argument("--qa-signer")
    args = parser.parse_args(argv)
    try:
        identity = inspect_apk(args.apk, args.aapt, args.apksigner)
        check_identity(identity, args.mode, args.qa_signer)
    except IdentityFailure as error:
        print(f"APK_IDENTITY_GATE_REJECTED: {error}", file=sys.stderr)
        return 1
    print(
        f"APK_IDENTITY_GATE_PASS mode={args.mode} package={identity.package} "
        f"versionCode={identity.version_code} signed=true "
        f"productionUpgradeApproved={str(args.mode == 'production').lower()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
