"""Offline contract tests for the disposable emulator's Base64 ADB transport."""
import base64
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_disposable_upgrade as qa


class DeviceTransferTests(unittest.TestCase):
    def test_writes_ascii_inside_run_as_without_shell_escaping_the_redirect(self):
        payload = b"SQLite format 3\x00\xfe\xff\0\x01\n" + bytes(range(256))
        with patch.object(qa, "adb") as command:
            qa.private_write("emulator-5554", "databases/elsewhere_database", payload)
        command.assert_called_once_with(
            "emulator-5554", "exec-in", "run-as", qa.PRODUCTION_PACKAGE,
            "sh", "-c", "'base64 -d > databases/elsewhere_database'",
            input_bytes=base64.b64encode(payload),
        )

    def test_reads_wrapped_ascii_base64_without_modifying_binary_data(self):
        payload = b"SQLite format 3\x00\xfe\xff\0\x01\n" + bytes(range(256))
        encoded = base64.b64encode(payload)
        wrapped = b"\n".join(encoded[i:i + 76] for i in range(0, len(encoded), 76))
        with patch.object(qa, "adb", return_value=wrapped):
            self.assertEqual(
                qa.private_read("emulator-5554", "databases/elsewhere_database"),
                payload,
            )

    def test_absent_optional_wal_is_not_mistaken_for_an_empty_database(self):
        with patch.object(qa, "adb", return_value=None):
            self.assertIsNone(
                qa.private_read("emulator-5554", "databases/elsewhere_database-wal",
                                optional=True)
            )

    def test_bad_ascii_is_an_error_not_silent_data_corruption(self):
        with patch.object(qa, "adb", return_value=b"garbled transcript!"):
            with self.assertRaisesRegex(RuntimeError, "not valid Base64"):
                qa.private_read("emulator-5554", "databases/elsewhere_database")

    def test_paths_cannot_escape_the_disposable_app_private_directories(self):
        for path in ("../data/elsewhere_database", "databases/../../other",
                     "/sdcard/secrets", "databases/data; rm -rf /",
                     "files/file name"):
            with self.subTest(path=path):
                with patch.object(qa, "adb") as command:
                    with self.assertRaisesRegex(RuntimeError, "Invalid disposable"):
                        qa.private_write("emulator-5554", path, b"example")
                    command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
