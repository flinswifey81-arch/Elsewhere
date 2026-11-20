#!/usr/bin/env python3
"""Offline APK identity audit. No APK install, private key, or production signing.

QA mode requires the SAME disposable CI certificate on the pinned v15 and
candidate APKs, and ensures that this disposable signer is NOT the pinned
production certificate. Production mode requires the genuine pinned signer,
correct package, and monotonically increasing Android versionCode.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str
    signer_sha256: str
    modern_apk_signature: bool


def parse_apk_identity(badging: str, verifier: str) -> ApkIdentity:
    package_lines = [line for line in badging.splitlines() if line.startswith("package:")]
    if len(package_lines) != 1:
        raise ValueError("APK badging must contain exactly one package declaration")
    line = package_lines[0]

    def value(name: str) -> str:
        match = re.search(r"\b" + re.escape(name) + r"='([^']*)'", line)
        if not match or not match.group(1):
            raise ValueError(f"Missing APK {name}")
        return match.group(1)

    code_text = value("versionCode")
    if not code_text.isdecimal():
        raise ValueError("APK versionCode must be a nonnegative integer")

    signers = re.findall(
        r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})\s*$",
        verifier,
        re.MULTILINE,
    )
    signer_count = re.findall(r"^Number of signers:\s*(\d+)\s*$", verifier, re.MULTILINE)
    if len(signers) != 1 or signers[0][0] != "1" or (
        signer_count and signer_count != ["1"]
    ):
        raise ValueError("Require exactly one verified APK signer with a SHA-256 certificate digest")

    modern = bool(
        re.search(
            r"^Verified using v[23] scheme\b.*:\s*true\s*$",
            verifier,
            re.MULTILINE | re.IGNORECASE,
        )
    )
    if not modern:
        raise ValueError("APK must pass v2 or v3 signature verification")

    return ApkIdentity(
        package=value("name"),
        version_code=int(code_text),
        version_name=value("versionName"),
        signer_sha256=signers[0][1].lower(),
        modern_apk_signature=modern,
    )


def inspect_apk(apk: Path, aapt: Path, apksigner: Path) -> ApkIdentity:
    if not apk.is_file() or not apk.stat().st_size:
        raise ValueError("Signed APK not found")
    badging = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        check=True, text=True, capture_output=True,
    ).stdout
    signatures = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, text=True, capture_output=True,
    ).stdout
    return parse_apk_identity(badging, signatures)


def evaluate(
    baseline: dict, candidate: ApkIdentity,
    mode: str, historical: ApkIdentity | None = None
) -> dict:
    required = {
        "historical_commit", "historical_version_code", "historical_version_name",
        "production_package", "historical_production_signer_sha256",
    }
    if not required.issubset(baseline):
        raise ValueError("Historical release contract is incomplete")
    expected_package = baseline["production_package"]
    expected_code = baseline["historical_version_code"]
    expected_name = baseline["historical_version_name"]
    expected_signer = baseline["historical_production_signer_sha256"].lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected_signer):
        raise ValueError("Invalid pinned public signer fingerprint")
    if not isinstance(expected_code, int) or expected_code <= 0:
        raise ValueError("Invalid historical release version code")
    if candidate.package != expected_package or not candidate.modern_apk_signature:
        raise ValueError("Candidate package or APK signature is incompatible with v15 identity")
    if candidate.version_code < expected_code:
        raise ValueError("Candidate Android versionCode must not downgrade below installed v15 baseline")
    if mode not in ("qa", "production"):
        raise ValueError("Unexpected audit mode")

    advances_version = candidate.version_code > expected_code
    uses_pinned_production_signer = candidate.signer_sha256 == expected_signer
    comparison = {
        "mode": mode,
        "historicalCommit": baseline["historical_commit"],
        "package": candidate.package,
        "historicalVersionCode": expected_code,
        "candidateVersionCode": candidate.version_code,
        "candidateVersionName": candidate.version_name,
        "samePackage": True,
        "versionCodeIncreases": advances_version,
        "usesPinnedProductionSigner": uses_pinned_production_signer,
        "verifiedModernSignature": True,
    }

    if mode == "qa":
        if historical is None:
            raise ValueError("QA mode requires an actual historical APK, signed with the disposable QA key")
        if historical.package != expected_package:
            raise ValueError("Historical APK package differs from version-controlled release baseline")
        if historical.version_code != expected_code or historical.version_name != expected_name:
            raise ValueError("Historical APK version differs from pinned release baseline")
        if not historical.modern_apk_signature:
            raise ValueError("Historical APK must have a valid modern APK signature")
        if historical.signer_sha256 != candidate.signer_sha256:
            raise ValueError("Disposable QA signing certificate drifted between v15 and candidate")
        if uses_pinned_production_signer:
            raise ValueError("Production signing key must never be present in QA identity workflow")
        comparison.update({
            "historicalAndCandidateShareDisposableQaSigner": True,
            "installedApkSignerConfirmed": False,
            "eligibleForInPlaceProductionUpdate": False,
            "status": "QA_CHAIN_VERIFIED_PRODUCTION_RELEASE_BLOCKED",
            "remainingBlockers": [
                "This is a disposable QA certificate, not the installed app certificate",
                *([] if advances_version else ["Candidate versionCode does not exceed historical versionCode"]),
                "No verified inspection of the owner's installed APK or disposable-device upgrade",
            ],
        })
    else:
        if not uses_pinned_production_signer:
            raise ValueError("Production candidate APK signer differs from pinned existing production certificate")
        if not advances_version:
            raise ValueError("Android in-place update requires a candidate versionCode higher than v15")
        comparison.update({
            "eligibleForInPlaceProductionUpdate": True,
            "installedApkSignerConfirmed": False,
            "status": "PACKAGE_VERSION_PINNED_CERTIFICATE_CHECK_PASS_DEVICE_NOT_YET_VERIFIED",
        })
    return comparison


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit signed APK identity without installing anything")
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--candidate-apk", required=True, type=Path)
    parser.add_argument("--historical-apk", type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--mode", required=True, choices=("qa", "production"))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        candidate = inspect_apk(args.candidate_apk, args.aapt, args.apksigner)
        historical = (
            inspect_apk(args.historical_apk, args.aapt, args.apksigner)
            if args.historical_apk is not None else None
        )
        report = evaluate(contract, candidate, args.mode, historical)
        print(json.dumps(report, indent=2, sort_keys=True))
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print("APK_IDENTITY_CHECK_BLOCKED:", type(exc).__name__, str(exc))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
