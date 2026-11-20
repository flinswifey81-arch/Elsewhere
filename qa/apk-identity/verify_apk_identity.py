#!/usr/bin/env python3
"""Read-only APK package/version/signing gate. Never signs or installs anything.

The production SHA-256 is pinned from the historical v15 release workflow,
NOT inferred from the candidate, its keystore, or a build-environment variable.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import re
import subprocess
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

PRODUCTION_PACKAGE = "com.aistudio.elsewhere.abwzy.v15"
SANDBOX_PACKAGE = "com.aistudio.elsewhere.abwzy.test"
PRODUCTION_V15_SIGNER = "7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5"
INSTALLED_V15_VERSION_CODE = 3
INSTALLED_V15_VERSION_NAME = "1.5.2"


class GateFailure(ValueError):
    def __init__(self, code: str, detail: str):
        super().__init__(detail)
        self.code = code


@dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str
    signer_sha256: str
    v2_or_v3_signed: bool


_PACKAGE = re.compile(
    r"^package:\s+name='([^']+)'\s+versionCode='([0-9]+)'\s+versionName='([^']+)'",
    re.MULTILINE,
)
_SIGNER = re.compile(r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", re.MULTILINE)
_SIGNED_V2_V3 = re.compile(
    r"^Verified using v[23] scheme\s*\([^\r\n]*\):\s*true\s*$",
    re.MULTILINE | re.IGNORECASE,
)


def read_identity(badging: str, signature_report: str) -> ApkIdentity:
    packages = _PACKAGE.findall(badging)
    if len(packages) != 1:
        raise GateFailure("bad-manifest", "Expected exactly one valid aapt package declaration")
    signers = _SIGNER.findall(signature_report)
    if len(signers) != 1 or signers[0][0] != "1":
        raise GateFailure("signer-count", "APK requires exactly one unambiguous signing certificate")
    if not _SIGNED_V2_V3.search(signature_report):
        raise GateFailure("signature-scheme", "APK must have a verified v2 or v3 signature")
    package, code, name = packages[0]
    return ApkIdentity(package, int(code), name, signers[0][1].lower(), True)


def verify_policy(
    apk: ApkIdentity, mode: str, *, expected_test_signer: str | None = None,
    installed_test_code: int | None = None
) -> None:
    if mode == "production-update":
        if apk.package != PRODUCTION_PACKAGE:
            raise GateFailure("production-package-mismatch", "Production package ID differs from installed Elsewhere")
        # Check the trusted historical fingerprint BEFORE asking whether the
        # code is high enough. A newly generated key MUST NOT pass as an update.
        if apk.signer_sha256 != PRODUCTION_V15_SIGNER:
            raise GateFailure("production-signer-mismatch", "Signer cannot update installed Elsewhere")
        if apk.version_code <= INSTALLED_V15_VERSION_CODE:
            raise GateFailure("production-version-not-increased", "A new release must increment versionCode beyond installed v15")
        if apk.version_name == INSTALLED_V15_VERSION_NAME or apk.version_name.endswith("-test"):
            raise GateFailure("production-version-name", "Production update still advertises the old/test version name")
        return

    if mode not in ("sandbox-isolation", "test-update"):
        raise GateFailure("invalid-mode", "Unrecognized verification mode")
    if apk.package != SANDBOX_PACKAGE or apk.package == PRODUCTION_PACKAGE:
        raise GateFailure("sandbox-package-mismatch", "Testing APK must use its isolated, stable package ID")
    if apk.signer_sha256 == PRODUCTION_V15_SIGNER:
        raise GateFailure("sandbox-production-signer", "Testing APK must not reuse the production signing identity")
    if not apk.version_name.endswith("-test"):
        raise GateFailure("sandbox-version-name", "Testing APK must be visibly marked as a Test build")
    if mode == "test-update":
        if expected_test_signer is None or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_test_signer):
            raise GateFailure("missing-test-baseline", "Cannot prove Test upgrade compatibility without the installed Test signer fingerprint")
        if installed_test_code is None or installed_test_code < 1:
            raise GateFailure("missing-test-version", "Cannot prove Test upgrade compatibility without the installed Test versionCode")
        if apk.signer_sha256 != expected_test_signer.lower():
            raise GateFailure("test-signer-mismatch", "Testing signer differs from installed Test APK")
        if apk.version_code <= installed_test_code:
            raise GateFailure("test-version-not-increased", "Testing update requires a higher versionCode")


def check_native_libraries(apk_path: Path) -> int:
    try:
        with ZipFile(apk_path) as archive:
            libs = [item for item in archive.infolist()
                    if item.filename.startswith("lib/") and item.filename.endswith(".so")]
            if not libs:
                raise GateFailure("missing-native-libs", "Expected compiled native libraries")
            if any(item.compress_type != ZIP_DEFLATED for item in libs):
                raise GateFailure("native-lib-packaging", "Native libraries are not all compressed for 16 KB-page compatibility")
            return len(libs)
    except GateFailure:
        raise
    except (OSError, ValueError) as error:
        raise GateFailure("bad-apk-zip", "APK archive is unreadable") from error


def apk_tool_identity(apk: Path, aapt: str, apksigner: str) -> ApkIdentity:
    if not apk.is_file() or not apk.stat().st_size:
        raise GateFailure("missing-apk", "APK does not exist or is empty")

    def call(*args: str) -> str:
        completed = subprocess.run(args, text=True, capture_output=True, check=False)
        if completed.returncode != 0:
            raise GateFailure("apk-tool-failed", f"Android verification tool exited nonzero: {Path(args[0]).name}")
        return completed.stdout

    badging = call(aapt, "dump", "badging", str(apk))
    signed = call(apksigner, "verify", "--verbose", "--print-certs", str(apk))
    return read_identity(badging, signed)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--mode", required=True, choices=("production-update", "sandbox-isolation", "test-update"))
    parser.add_argument("--expected-test-signer")
    parser.add_argument("--installed-test-code", type=int)
    args = parser.parse_args()
    try:
        identity = apk_tool_identity(args.apk, args.aapt, args.apksigner)
        verify_policy(
            identity, args.mode,
            expected_test_signer=args.expected_test_signer,
            installed_test_code=args.installed_test_code
        )
        native_count = check_native_libraries(args.apk)
    except GateFailure as failure:
        print(f"APK_IDENTITY_BLOCKED reason={failure.code} (no install performed)")
        return 1

    print(
        f"APK_IDENTITY_PASS mode={args.mode} package={identity.package} "
        f"versionCode={identity.version_code} versionName={identity.version_name} "
        f"signerSha256={identity.signer_sha256} nativeLibs={native_count} "
        "installedAppTouched=false"
    )
    if args.mode == "sandbox-isolation":
        print("QA_SANDBOX_ONLY: This result does not validate the signer of any installed Test app.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
