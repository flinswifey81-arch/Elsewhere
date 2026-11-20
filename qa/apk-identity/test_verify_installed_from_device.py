"""Mocked tests: owner phone is never attached, accessed, or needed in CI."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_installed_from_device as local
from verify_apk_identity import (
    ApkIdentity, GateFailure, PRODUCTION_PACKAGE, PRODUCTION_V15_SIGNER,
)


SERIAL = "PIXEL12345"
INSTALLED_PATH = "/data/app/~~alpha/com.aistudio.elsewhere.abwzy.v15-abc/base.apk"
INSTALLED = ApkIdentity(PRODUCTION_PACKAGE, 3, "1.5.2",
                        PRODUCTION_V15_SIGNER, True, False)
CANDIDATE = ApkIdentity(PRODUCTION_PACKAGE, 4, "1.6.0",
                        PRODUCTION_V15_SIGNER, True, False)


class FakeADB:
    def __init__(self, *, qemu="0", package_output=None, connected=True):
        self.calls = []
        self.temp_copies = []
        self.qemu = qemu
        self.connected = connected
        self.package_output = package_output or (
            "package:" + INSTALLED_PATH + "\n"
            "package:/data/app/~~alpha/com.aistudio.elsewhere.abwzy.v15-abc/split_config.arm64_v8a.apk\n"
        )

    def __call__(self, *args, timeout=40):
        self.calls.append(args)
        if args == ("devices",):
            return f"List of devices attached\n{SERIAL}\t{'device' if self.connected else 'unauthorized'}\n\n"
        if args == ("-s", SERIAL, "shell", "getprop", "ro.kernel.qemu"):
            return self.qemu + "\n"
        if args == ("-s", SERIAL, "shell", "pm", "path", PRODUCTION_PACKAGE):
            return self.package_output
        if args[:3] == ("-s", SERIAL, "pull") and len(args) == 5:
            Path(args[4]).write_bytes(b"Synthetic not real APK")
            self.temp_copies.append(Path(args[4]))
            return "1 file pulled"
        raise AssertionError(f"Unexpected ADB command: {args}")


class InstalledReadOnlyIdentityTests(unittest.TestCase):
    def test_inspection_copies_only_base_apk_and_deletes_it_after_success(self):
        adb = FakeADB()
        with patch.object(local, "run_adb", side_effect=adb), \
                patch.object(local, "apk_tool_identity", return_value=INSTALLED) as apk:
            result = local.inspect_installed(SERIAL, "/sdk/aapt", "/sdk/apksigner")
        self.assertEqual(INSTALLED, result)
        self.assertEqual(1, apk.call_count)
        self.assertEqual(1, len(adb.temp_copies))
        self.assertFalse(adb.temp_copies[0].exists(), "temporary APK must be deleted")
        self.assertEqual(
            ("-s", SERIAL, "pull", INSTALLED_PATH, str(adb.temp_copies[0])),
            adb.calls[-1],
        )

    def test_inspection_also_compares_finished_candidate_when_supplied(self):
        adb = FakeADB()
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as temp:
            candidate = Path(temp) / "release.apk"
            candidate.write_bytes(b"synthetic only")
            with patch.object(local, "run_adb", side_effect=adb), \
                    patch.object(local, "apk_tool_identity",
                                 side_effect=[INSTALLED, CANDIDATE]) as apk, \
                    patch.object(local, "check_native_libraries", return_value=13) as libs:
                result = local.inspect_installed(SERIAL, "aapt", "apksigner", candidate)
            self.assertEqual(INSTALLED, result)
            self.assertEqual(2, apk.call_count)
            libs.assert_called_once_with(candidate)
        self.assertFalse(adb.temp_copies[0].exists())

    def test_bad_installed_certificate_fails_and_cleans_temp_file(self):
        adb = FakeADB()
        invalid = ApkIdentity(PRODUCTION_PACKAGE, 3, "1.5.2", "0" * 64, True, False)
        with patch.object(local, "run_adb", side_effect=adb), \
                patch.object(local, "apk_tool_identity", return_value=invalid):
            with self.assertRaises(GateFailure) as failure:
                local.inspect_installed(SERIAL, "aapt", "apksigner")
        self.assertEqual("installed-signer-mismatch", failure.exception.code)
        self.assertFalse(adb.temp_copies[0].exists())

    def test_failure_to_parse_installed_apk_also_cleans_temp_file(self):
        adb = FakeADB()
        with patch.object(local, "run_adb", side_effect=adb), \
                patch.object(local, "apk_tool_identity",
                             side_effect=GateFailure("bad-manifest", "synthetic")):
            with self.assertRaises(GateFailure):
                local.inspect_installed(SERIAL, "aapt", "apksigner")
        self.assertFalse(adb.temp_copies[0].exists())

    def test_untrusted_serial_refused_before_any_adb_command(self):
        for serial in ("emulator-5554", "", "-malicious", "A B", "../device"):
            with self.subTest(serial=serial):
                with patch.object(local, "run_adb",
                                  side_effect=AssertionError("Must not call ADB")):
                    with self.assertRaises(local.LocalInspectionError):
                        local.check_selected_device(serial)

    def test_unauthorized_or_offline_device_never_exposes_apk_path(self):
        adb = FakeADB(connected=False)
        with patch.object(local, "run_adb", side_effect=adb):
            with self.assertRaises(local.LocalInspectionError):
                local.inspect_installed(SERIAL, "aapt", "apksigner")
        self.assertEqual([("devices",)], adb.calls)

    def test_qemu_emulator_refused_even_with_physical_looking_serial(self):
        adb = FakeADB(qemu="1")
        with patch.object(local, "run_adb", side_effect=adb):
            with self.assertRaisesRegex(local.LocalInspectionError, "emulator"):
                local.inspect_installed(SERIAL, "aapt", "apksigner")
        self.assertEqual(2, len(adb.calls))

    def test_base_apk_must_be_unique_and_under_android_app_path(self):
        invalid = (
            "",
            "package:/sdcard/download/base.apk\n",
            "package:/data/app/../private/base.apk\n",
            "package:/data/app/one/base.apk\npackage:/data/app/two/base.apk\n",
            "package:/data/app/one/split_config.arm64.apk\n",
        )
        for output in invalid:
            with self.subTest(output=output):
                adb = FakeADB(package_output=output)
                # FakeADB defaults on falsey input; directly assign empty output.
                adb.package_output = output
                with patch.object(local, "run_adb", side_effect=adb):
                    with self.assertRaises(local.LocalInspectionError):
                        local.inspect_installed(SERIAL, "aapt", "apksigner")
                self.assertNotIn("pull", [call[2] for call in adb.calls if len(call) > 2])

    def test_adb_command_allowlist_rejects_install_uninstall_root_or_data_access(self):
        forbidden = [
            ("-s", SERIAL, "install", "some.apk"),
            ("-s", SERIAL, "uninstall", PRODUCTION_PACKAGE),
            ("-s", SERIAL, "root"),
            ("-s", SERIAL, "shell", "run-as", PRODUCTION_PACKAGE, "cat", "databases/db"),
            ("-s", SERIAL, "push", "source", "destination"),
            ("shell", "pm", "path", PRODUCTION_PACKAGE),
        ]
        for args in forbidden:
            with self.subTest(args=args):
                with patch.object(local.subprocess, "run",
                                  side_effect=AssertionError("Must not execute")):
                    with self.assertRaises(local.LocalInspectionError):
                        local.run_adb(*args)

    def test_adb_device_query_is_read_only_and_explicit(self):
        result = subprocess.CompletedProcess([], 0,
                    "List of devices attached\nPIXEL12345\tdevice\n", "")
        with patch.object(local.subprocess, "run", return_value=result) as run:
            self.assertIn("PIXEL12345", local.run_adb("devices"))
        self.assertEqual(("adb", "devices"), run.call_args.args[0])

    def test_cli_rejects_mismatch_without_claiming_success(self):
        argv = ["verify_installed_from_device.py", "--serial", SERIAL,
                "--aapt", "aapt", "--apksigner", "apksigner"]
        stdout = StringIO()
        with patch.object(sys, "argv", argv), \
                patch.object(local, "inspect_installed",
                             side_effect=GateFailure("installed-signer-mismatch", "synthetic")), \
                redirect_stdout(stdout):
            self.assertEqual(1, local.main())
        self.assertIn("INSTALLED_BASELINE_NOT_VERIFIED reason=installed-signer-mismatch",
                      stdout.getvalue())
        self.assertNotIn("IDENTITY_PASS", stdout.getvalue())

    def test_cli_reports_only_nonsecret_identity_not_any_private_apk_path(self):
        argv = ["verify_installed_from_device.py", "--serial", SERIAL,
                "--aapt", "aapt", "--apksigner", "apksigner"]
        stdout = StringIO()
        with patch.object(sys, "argv", argv), \
                patch.object(local, "inspect_installed", return_value=INSTALLED), \
                redirect_stdout(stdout):
            self.assertEqual(0, local.main())
        output = stdout.getvalue()
        self.assertIn("INSTALLED_ELSEWHERE_V15_IDENTITY_PASS", output)
        self.assertIn(PRODUCTION_V15_SIGNER, output)
        self.assertNotIn("/data/app", output)
        self.assertIn("no installation", output)


if __name__ == "__main__":
    unittest.main()
