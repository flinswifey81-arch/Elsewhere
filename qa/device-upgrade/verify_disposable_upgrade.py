#!/usr/bin/env python3
"""CI-only Android emulator package upgrade rehearsal with synthetic v15 Room data.

NEVER run on an owner's real phone. All APKs are disposable DEBUG builds
using the same temporary debug signing identity. No production keys, backups,
accounts, paid provider calls, installation artifacts or private data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "apk-identity"))
from verify_apk_identity import (
    PRODUCTION_PACKAGE, PRODUCTION_V15_SIGNER,
    INSTALLED_V15_VERSION_CODE, INSTALLED_V15_VERSION_NAME,
    apk_tool_identity,
)

QA_VERSION_CODE = 4  # Same version as prepared candidate; still an isolated debug-signing rehearsal.
QA_VERSION_NAME = "1.6.0"
DATABASE = "elsewhere_database"
SENTINEL = b"Elsewhere-synthetic-emulator-file-survived-package-upgrade-v1\n"


def identity_gate(old: Path, new: Path, fixture: Path, aapt: str, apksigner: str) -> None:
    for file in (old, new, fixture):
        if not file.is_file() or file.stat().st_size < 100:
            raise RuntimeError(f"Missing or empty QA input: {file}")
    original = apk_tool_identity(old, aapt, apksigner)
    candidate = apk_tool_identity(new, aapt, apksigner)
    for apk in (original, candidate):
        if apk.package != PRODUCTION_PACKAGE or not apk.debuggable:
            raise RuntimeError("QA rehearsal requires debuggable builds of the exact historical package")
        if apk.signer_sha256 == PRODUCTION_V15_SIGNER:
            raise RuntimeError("QA build unexpectedly has the historical production signing certificate")
    if original.signer_sha256 != candidate.signer_sha256:
        raise RuntimeError("Android would reject this QA upgrade: debug signing certificates differ")
    if (original.version_code, original.version_name) != (
        INSTALLED_V15_VERSION_CODE, INSTALLED_V15_VERSION_NAME
    ):
        raise RuntimeError("Wrong original version: rehearsal requires pinned v1.5.2 (3)")
    if (candidate.version_code, candidate.version_name) != (QA_VERSION_CODE, QA_VERSION_NAME):
        raise RuntimeError("Candidate debug build does not match prepared release version 1.6.0 (4)")
    with sqlite3.connect(f"file:{fixture}?mode=ro", uri=True) as conn:
        if conn.execute("PRAGMA user_version").fetchone()[0] != 15:
            raise RuntimeError("Synthetic fixture was not created by Room v15")
        if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("Synthetic v15 fixture fails SQLite integrity check")
        tables = ordinary_tables(conn)
        if len(tables) < 30:
            raise RuntimeError("Incomplete v15 schema fixture")
    print("QA_PRECHECK_PASS: two disposable debug APKs use matching non-production signer, "
          "package and increasing candidate version; synthetic Room v15 fixture valid")


def ordinary_tables(db: sqlite3.Connection) -> list[str]:
    return sorted(row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%' AND name!='room_master_table'"
    ))


def record_rows(db: sqlite3.Connection, table: str, columns: list[str]) -> str:
    quoted = lambda s: '"' + s.replace('"', '""') + '"'
    query = "SELECT " + ", ".join(map(quoted, columns)) + " FROM " + quoted(table)
    values = db.execute(query).fetchall()
    def normal(value):
        return {"bytes": value.hex()} if isinstance(value, bytes) else value
    encoded = sorted(json.dumps([normal(v) for v in row], ensure_ascii=True)
                     for row in values)
    return hashlib.sha256(json.dumps(encoded, separators=(",", ":")).encode()).hexdigest()


def snapshot(database_file: Path, old_columns: dict[str, list[str]] | None = None):
    with sqlite3.connect(f"file:{database_file}?mode=ro", uri=True) as db:
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("Device SQLite integrity_check failed")
        version = db.execute("PRAGMA user_version").fetchone()[0]
        tables = ordinary_tables(db)
        columns = old_columns or {
            table: [row[1] for row in db.execute(
                'PRAGMA table_info("' + table.replace('"', '""') + '")'
            )] for table in tables
        }
        if any(not cols for cols in columns.values()):
            raise RuntimeError("Missing historical table columns")
        digests = {table: record_rows(db, table, cols) for table, cols in columns.items()}
        return version, columns, digests


def adb(serial: str, *args: str, input_bytes: bytes | None = None, optional: bool = False):
    result = subprocess.run(["adb", "-s", serial, *args], input=input_bytes,
                            capture_output=True, check=False)
    if result.returncode and not optional:
        # Avoid disclosing private data: include only command type and exit code.
        raise RuntimeError(f"Disposable emulator adb step {args[:2]!r} failed, code {result.returncode}")
    return result.stdout if result.returncode == 0 else None


def only_emulator() -> str:
    if os.getenv("GITHUB_ACTIONS") != "true" or os.getenv("ELSEWHERE_DISPOSABLE_EMULATOR_ONLY") != "1":
        raise RuntimeError("Device rehearsal is restricted to an explicitly opted-in GitHub Actions emulator")
    devices = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    active = [line.split()[0] for line in devices.splitlines()[1:]
              if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(active) != 1 or not active[0].startswith("emulator-"):
        raise RuntimeError("Refusing execution unless exactly one Android emulator is connected")
    serial = active[0]
    if adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("Refusing execution: target does not report an Android emulator")
    return serial


def require_rooted_disposable_emulator(serial: str) -> None:
    """Permit root only on a verified, disposable AOSP CI emulator."""
    if only_emulator() != serial:
        raise RuntimeError("ADB target changed before isolated emulator root check")
    build_type = adb(serial, "shell", "getprop", "ro.build.type").strip()
    if build_type not in (b"userdebug", b"eng"):
        raise RuntimeError("Refusing root: AOSP userdebug/eng emulator required")
    # CI images can report sys.boot_completed before adbd is ready to
    # restart as root. Retry ONLY after the strict disposable-emulator and
    # userdebug/eng checks; never relax those checks or accept a real device.
    root_ready = False
    for attempt in range(4):
        command = subprocess.run(
            ["adb", "-s", serial, "root"], capture_output=True, check=False
        )
        if command.returncode == 0:
            root_ready = True
            break
        if attempt < 3:
            time.sleep(2)
            if only_emulator() != serial:
                raise RuntimeError("ADB target changed during emulator-only root setup")
    if not root_ready:
        raise RuntimeError("Disposable emulator adbd could not restart as root")
    adb(serial, "wait-for-device")
    if only_emulator() != serial:
        raise RuntimeError("ADB target changed during emulator-only root setup")
    if adb(serial, "shell", "id", "-u").strip() != b"0":
        raise RuntimeError("Disposable emulator adbd did not obtain root permissions")


def private_path(relpath: str) -> str:
    """Restrict all privileged transfers to a QA-controlled, app-private file."""
    if (not re.fullmatch(r"(databases|files)/[A-Za-z0-9_.-]+", relpath)
            or relpath.rsplit("/", 1)[-1] in (".", "..")):
        raise RuntimeError("Invalid disposable app-private QA path")
    return relpath


def app_data_dir(serial: str) -> str:
    # Resolve the real debuggable package directory, not a guessed alias.
    actual = adb(serial, "shell", "run-as", PRODUCTION_PACKAGE, "pwd")
    directory = actual.decode("utf-8", "replace").strip()
    allowed = {
        f"/data/user/0/{PRODUCTION_PACKAGE}",
        f"/data/data/{PRODUCTION_PACKAGE}",
    }
    if directory not in allowed:
        raise RuntimeError("Emulator returned unexpected app-private directory")
    return directory


def private_file(serial: str, relpath: str) -> str:
    # Validate the name before even querying the privileged app directory.
    safe_relpath = private_path(relpath)
    return str(PurePosixPath(app_data_dir(serial)) / safe_relpath)


def private_write(serial: str, relpath: str, data: bytes) -> None:
    """ADB binary push, then ownership, SELinux and device SHA-256 checks.

    Previous emulator runs returned empty/malformed bytes from streaming
    via ADB command stdin/stdout. Never transport a SQLite database that way.
    """
    destination = private_file(serial, relpath)
    with tempfile.TemporaryDirectory(prefix="elsewhere-qa-adb-push-") as directory:
        source = Path(directory) / "synthetic-only.bin"
        source.write_bytes(data)
        adb(serial, "push", str(source), destination)

    # Root-owned files are not writable by the app. Restore UID/GID and
    # the app-data SELinux label before attempting an on-device Room open.
    owner = adb(serial, "shell", "stat", "-c", "%u:%g",
                app_data_dir(serial)).decode("ascii", "replace").strip()
    if not re.fullmatch(r"[0-9]+:[0-9]+", owner):
        raise RuntimeError("Unexpected disposable app data owner")
    app_uid, app_gid = (int(value) for value in owner.split(":"))
    if app_uid < 10_000 or app_gid < 10_000:
        raise RuntimeError("Refusing to stage app files with system/root ownership")
    adb(serial, "shell", "chown", owner, destination)
    adb(serial, "shell", "chmod", "600", destination)
    adb(serial, "shell", "restorecon", "-R",
        str(PurePosixPath(destination).parent))

    expected_sha256 = hashlib.sha256(data).hexdigest()
    device_report = adb(serial, "shell", "sha256sum", destination)
    digest_tokens = device_report.decode("ascii", "replace").strip().split()
    if not digest_tokens or not re.fullmatch(r"[0-9a-fA-F]{64}", digest_tokens[0]):
        raise RuntimeError("Device did not return a valid synthetic file SHA-256")
    device_sha256 = digest_tokens[0].lower()
    if device_sha256 != expected_sha256:
        raise RuntimeError(
            "ADB push changed synthetic app-private file: "
            f"expectedSha256={expected_sha256} deviceSha256={device_sha256}"
        )


def private_read(serial: str, relpath: str, optional: bool = False) -> bytes | None:
    """Pull the real app-private file as binary, never through shell stdout."""
    source = private_file(serial, relpath)
    with tempfile.TemporaryDirectory(prefix="elsewhere-qa-adb-pull-") as directory:
        target = Path(directory) / "synthetic-only.bin"
        result = adb(serial, "pull", source, str(target), optional=optional)
        if result is None:
            return None
        if not target.is_file():
            raise RuntimeError("ADB pull succeeded without creating the local QA file")
        return target.read_bytes()


def copy_device_database(serial: str, folder: Path) -> Path:
    target = folder / DATABASE
    original = private_read(serial, f"databases/{DATABASE}")
    if not original:
        raise RuntimeError("Could not read disposable emulator database")
    target.write_bytes(original)
    # SQLite's -shm is a device-specific transient shared-memory index;
    # copying it to a host SQLite process is neither required nor safe.
    # The host rebuilds its own WAL index from the checkpointable -wal log.
    wal = private_read(serial, f"databases/{DATABASE}-wal", optional=True)
    if wal:
        (folder / (DATABASE + "-wal")).write_bytes(wal)
    return target


def rehearsal(old: Path, new: Path, test_apk: Path, fixture: Path) -> None:
    serial = only_emulator()
    # The Android guest is deliberately disposable. Never root another device.
    require_rooted_disposable_emulator(serial)
    if not test_apk.is_file():
        raise RuntimeError("Missing temporary instrumentation APK")
    # All steps occur on the fresh, verified emulator. No real-device adb target.
    adb(serial, "install", str(old))
    adb(serial, "shell", "run-as", PRODUCTION_PACKAGE, "mkdir", "-p", "databases", "files")
    # The historical full-schema database is already populated by real Room
    # v15 in a separate checkout. Before *any* app process starts, verify that
    # adb's binary transfer preserves the exact bytes, and establish a clean
    # pre-upgrade database snapshot. A prior test launched the v15 app first and
    # then sampled its WAL files independently; that snapshot was malformed.
    fixture_bytes = fixture.read_bytes()
    private_write(serial, f"databases/{DATABASE}", fixture_bytes)
    echoed = private_read(serial, f"databases/{DATABASE}")
    if echoed != fixture_bytes:
        expected_digest = hashlib.sha256(fixture_bytes).hexdigest()
        actual_digest = hashlib.sha256(echoed or b"").hexdigest()
        raise RuntimeError(
            "Emulator v15 fixture transfer mismatch (synthetic only): "
            f"sourceBytes={len(fixture_bytes)} deviceBytes={len(echoed or b'')} "
            f"sourceSha256={expected_digest} deviceSha256={actual_digest}"
        )
    private_write(serial, "files/qa-upgrade-marker", SENTINEL)

    with tempfile.TemporaryDirectory(prefix="elsewhere-disposable-upgrade-") as td:
        before = Path(td) / "before"
        after = Path(td) / "after"
        before.mkdir()
        after.mkdir()
        old_version, columns, old_digests = snapshot(copy_device_database(serial, before))
        if old_version != 15 or len(columns) < 30:
            raise RuntimeError("Emulator baseline is not the complete historical Room v15 database")
        if private_read(serial, "files/qa-upgrade-marker") != SENTINEL:
            raise RuntimeError("Synthetic app-private marker was not seeded")
        print("EMULATOR_V15_STAGING_PASS: complete original Room v15 database byte-identical "
              "after Android app-private transfer; no production/user data")

        # The -r transaction is the behavior this test actually exercises.
        # The candidate uses the prepared version but is debug-signed, NOT production-signed.
        adb(serial, "install", "-r", str(new))
        if private_read(serial, "files/qa-upgrade-marker") != SENTINEL:
            raise RuntimeError("Android update discarded the original app's private file")
        adb(serial, "install", str(test_apk))
        runner = PRODUCTION_PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
        output = adb(serial, "shell", "am", "instrument", "-w", "-r", "-e",
                     "class", "com.example.UpgradeRehearsalInstrumentedTest,com.example.ChatPdfExportInstrumentedTest", runner)
        result = output.decode("utf-8", "replace")
        if "OK (2 tests)" not in result or "INSTRUMENTATION_CODE: -1" not in result:
            raise RuntimeError("Native v24 migration and real Android PDF instrumentation did not pass")
        adb(serial, "shell", "am", "force-stop", PRODUCTION_PACKAGE)
        new_version, _, new_digests = snapshot(copy_device_database(serial, after), columns)
        if new_version != 24:
            raise RuntimeError(f"Emulator Room migration ended on schema {new_version}, not v24")
        changed = sorted(table for table, digest in old_digests.items()
                         if new_digests.get(table) != digest)
        if changed:
            raise RuntimeError(f"Emulator upgrade changed original table rows: {changed}")
        if private_read(serial, "files/qa-upgrade-marker") != SENTINEL:
            raise RuntimeError("Original private file disappeared after app migration")
        print(f"ANDROID_EMULATOR_V15_TO_V21_UPGRADE_PASS: in-place adb install -r; "
              f"{len(old_digests)} original v15 tables' original columns and rows preserved; "
              "schema v24; app-private file retained; no owner data or production signer")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--aapt", required=True)
    p.add_argument("--apksigner", required=True)
    p.add_argument("--test-apk", type=Path)
    p.add_argument("--on-emulator", action="store_true")
    args = p.parse_args()
    identity_gate(args.baseline, args.candidate, args.fixture, args.aapt, args.apksigner)
    if args.on_emulator:
        if not args.test_apk:
            p.error("--test-apk is required for emulator runs")
        rehearsal(args.baseline, args.candidate, args.test_apk, args.fixture)


if __name__ == "__main__":
    main()
