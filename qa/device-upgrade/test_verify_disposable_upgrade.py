"""Offline contract tests for the emulator-only rooted adb push/pull QA harness."""
import hashlib
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_disposable_upgrade as qa


class FakeEmulatorAdb:
    """Simulate adb's file-transfer API, not shell stdin/stdout."""
    def __init__(self, break_digest=False, owner="10145:10145"):
        self.data = {}
        self.calls = []
        self.break_digest = break_digest
        self.owner = owner
        self.root = f"/data/user/0/{qa.PRODUCTION_PACKAGE}"

    def __call__(self, serial, *args, **kwargs):
        assert serial == "emulator-5554", serial
        self.calls.append((args, kwargs))
        if args == ("shell", "run-as", qa.PRODUCTION_PACKAGE, "pwd"):
            return (self.root + "\n").encode()
        if args == ("shell", "stat", "-c", "%u:%g", self.root):
            return (self.owner + "\n").encode()
        if args[0] == "push":
            self.data[args[2]] = Path(args[1]).read_bytes()
            return b"pushed"
        if args[0] == "pull":
            payload = self.data.get(args[1])
            if payload is None and kwargs.get("optional"):
                return None
            if payload is None:
                raise AssertionError("Required test file absent")
            Path(args[2]).write_bytes(payload)
            return b"pulled"
        if args[:2] == ("shell", "sha256sum"):
            digest = ("0" * 64 if self.break_digest else
                      hashlib.sha256(self.data[args[2]]).hexdigest())
            return f"{digest}  {args[2]}\n".encode()
        if args[:2] in (("shell", "chown"), ("shell", "chmod"),
                        ("shell", "restorecon")):
            return b""
        raise AssertionError(f"Unexpected ADB command: {args}")


class DeviceTransferTests(unittest.TestCase):
    def test_real_binary_payload_is_pushed_pulled_and_checked(self):
        # Include SQLite NUL bytes, invalid UTF-8 and enough data to catch
        # partial transfers, not merely ASCII-only file behavior.
        payload = b"SQLite format 3\x00" + bytes(range(256)) * 1650
        fake = FakeEmulatorAdb()
        with patch.object(qa, "adb", side_effect=fake):
            qa.private_write("emulator-5554", "databases/elsewhere_database",
                             payload)
            received = qa.private_read("emulator-5554", "databases/elsewhere_database")
        self.assertEqual(payload, received)
        kinds = [call[0][0] for call in fake.calls]
        self.assertIn("push", kinds)
        self.assertIn("pull", kinds)
        self.assertNotIn("exec-in", kinds)
        self.assertNotIn("exec-out", kinds)
        self.assertIn(("shell", "chown", "10145:10145",
                       fake.root + "/databases/elsewhere_database"),
                      [args for args, _ in fake.calls])
        self.assertIn(("shell", "chmod", "600",
                       fake.root + "/databases/elsewhere_database"),
                      [args for args, _ in fake.calls])
        self.assertIn(("shell", "restorecon", "-R",
                       fake.root + "/databases"),
                      [args for args, _ in fake.calls])

    def test_remote_sha256_mismatch_rejects_staging(self):
        fake = FakeEmulatorAdb(break_digest=True)
        with patch.object(qa, "adb", side_effect=fake):
            with self.assertRaisesRegex(RuntimeError, "ADB push changed synthetic"):
                qa.private_write("emulator-5554",
                                 "databases/elsewhere_database", b"synthetic-only")

    def test_root_or_system_owned_app_directory_fails_closed(self):
        for owner in ("0:0", "1000:1000", "not-an-owner"):
            with self.subTest(owner=owner):
                fake = FakeEmulatorAdb(owner=owner)
                with patch.object(qa, "adb", side_effect=fake):
                    with self.assertRaisesRegex(RuntimeError, "owner|ownership"):
                        qa.private_write("emulator-5554",
                                         "databases/elsewhere_database", b"synthetic")
                self.assertNotIn(("shell", "chown", owner,
                    fake.root + "/databases/elsewhere_database"),
                    [args for args, _ in fake.calls])

    def test_optional_absent_wal_is_not_considered_corruption(self):
        fake = FakeEmulatorAdb()
        with patch.object(qa, "adb", side_effect=fake):
            self.assertIsNone(
                qa.private_read("emulator-5554",
                                "databases/elsewhere_database-wal", optional=True))

    def test_missing_required_file_fails_closed(self):
        fake = FakeEmulatorAdb()
        with patch.object(qa, "adb", side_effect=fake):
            with self.assertRaisesRegex(AssertionError, "Required test file absent"):
                qa.private_read("emulator-5554", "databases/elsewhere_database")

    def test_traversal_refused_before_any_adb_command(self):
        for path in ("../data/elsewhere_database", "databases/../../other",
                     "/sdcard/secrets", "databases/data; rm -rf /",
                     "files/file name", "files/..", "files/.",
                     "files/subfolder/database"):
            with self.subTest(path=path):
                fake = FakeEmulatorAdb()
                with patch.object(qa, "adb", side_effect=fake):
                    with self.assertRaisesRegex(RuntimeError, "Invalid disposable"):
                        qa.private_write("emulator-5554", path, b"example")
                self.assertEqual([], fake.calls)

    def test_run_as_directory_must_match_pinned_package(self):
        fake = FakeEmulatorAdb()
        def wrong_root(serial, *args, **kwargs):
            if args == ("shell", "run-as", qa.PRODUCTION_PACKAGE, "pwd"):
                return b"/data/user/0/another.app\n"
            return fake(serial, *args, **kwargs)
        with patch.object(qa, "adb", side_effect=wrong_root):
            with self.assertRaisesRegex(RuntimeError, "unexpected app-private directory"):
                qa.private_read("emulator-5554", "files/qa-upgrade-marker")

    def test_root_never_requested_for_a_non_userdebug_image(self):
        fake_calls = []
        def user_image(serial, *args, **kwargs):
            fake_calls.append(args)
            if args == ("shell", "getprop", "ro.build.type"):
                return b"user\n"
            raise AssertionError("A production build must not reach adb root")
        with patch.object(qa, "only_emulator", return_value="emulator-5554"):
            with patch.object(qa, "adb", side_effect=user_image):
                with self.assertRaisesRegex(RuntimeError, "userdebug/eng"):
                    qa.require_rooted_disposable_emulator("emulator-5554")
        self.assertNotIn(("root",), fake_calls)

    def test_root_requires_verified_emulator_after_adbd_restart(self):
        calls = []
        def root_adb(serial, *args, **kwargs):
            calls.append(args)
            return {
                ("shell", "getprop", "ro.build.type"): b"userdebug\n",
                ("root",): b"restarting adbd as root",
                ("wait-for-device",): b"",
                ("shell", "id", "-u"): b"0\n",
            }[args]
        with patch.object(qa, "only_emulator", return_value="emulator-5554") as verify:
            with patch.object(qa, "adb", side_effect=root_adb):
                qa.require_rooted_disposable_emulator("emulator-5554")
        self.assertEqual(2, verify.call_count)
        self.assertEqual([("shell", "getprop", "ro.build.type"),
                          ("root",), ("wait-for-device",),
                          ("shell", "id", "-u")], calls)


if __name__ == "__main__":
    unittest.main()
