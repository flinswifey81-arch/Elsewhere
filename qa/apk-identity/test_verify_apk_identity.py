"""Offline unit checks for the fail-closed APK identity verifier.

Uses only synthetic aapt/apksigner reports, with no signing key or APK from a
user's phone. An additional CI job checks actual compiled APK tool output.
"""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_apk_identity import (
    ApkIdentity, GateFailure, PRODUCTION_PACKAGE, SANDBOX_PACKAGE,
    PRODUCTION_V15_SIGNER, read_identity, verify_policy, check_native_libraries,
    apk_tool_identity,
)

OTHER_SIGNER = "a" * 64
TEST_SIGNER = "b" * 64


def report(package=PRODUCTION_PACKAGE, code=4, name="1.6.0", signer=PRODUCTION_V15_SIGNER,
           v2=True, second_signer=None):
    badging = f"package: name='{package}' versionCode='{code}' versionName='{name}'\n"
    signatures = "Verifies\n"
    signatures += f"Verified using v2 scheme (APK Signature Scheme v2): {'true' if v2 else 'false'}\n"
    signatures += f"Signer #1 certificate SHA-256 digest: {signer}\n"
    if second_signer:
        signatures += f"Signer #2 certificate SHA-256 digest: {second_signer}\n"
    return badging, signatures


class IdentityGateTests(unittest.TestCase):
    def identity(self, **kwargs):
        return read_identity(*report(**kwargs))

    def assert_blocked(self, code, operation):
        with self.assertRaises(GateFailure) as err:
            operation()
        self.assertEqual(err.exception.code, code)

    def test_installed_152_apk_matches_frozen_identity_only(self):
        verify_policy(self.identity(code=3, name="1.5.2"), "installed-baseline")

    def test_installed_baseline_wrong_package_is_rejected(self):
        self.assert_blocked("installed-package-mismatch",
            lambda: verify_policy(self.identity(package=SANDBOX_PACKAGE, code=3, name="1.5.2"),
                                  "installed-baseline"))

    def test_installed_baseline_unexpected_version_is_rejected(self):
        for code, name in ((4, "1.5.2"), (3, "1.6.0"), (2, "1.5.1")):
            with self.subTest(code=code, name=name):
                self.assert_blocked("installed-version-unexpected",
                    lambda: verify_policy(self.identity(code=code, name=name), "installed-baseline"))

    def test_installed_baseline_foreign_signer_is_rejected(self):
        self.assert_blocked("installed-signer-mismatch",
            lambda: verify_policy(self.identity(code=3, name="1.5.2", signer=OTHER_SIGNER),
                                  "installed-baseline"))

    def test_installed_baseline_debuggable_is_rejected(self):
        badging, signatures = report(code=3, name="1.5.2")
        self.assert_blocked("installed-debuggable",
            lambda: verify_policy(read_identity(badging + "application-debuggable\n", signatures),
                                  "installed-baseline"))

    def test_installed_baseline_does_not_require_native_libs_in_base_split(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from verify_apk_identity import main
        identity = self.identity(code=3, name="1.5.2")
        output = StringIO()
        args = ["verify_apk_identity.py", "--apk", "/unused/base.apk", "--aapt", "/unused/aapt",
                "--apksigner", "/unused/apksigner", "--mode", "installed-baseline"]
        with patch.object(sys, "argv", args), \
                patch("verify_apk_identity.apk_tool_identity", return_value=identity), \
                patch("verify_apk_identity.check_native_libraries", side_effect=AssertionError("must not inspect split libs")), \
                redirect_stdout(output):
            self.assertEqual(0, main())
        self.assertIn("INSTALLED_V15_BASELINE_MATCH", output.getvalue())
        self.assertIn("nativeLibs=not-checked", output.getvalue())

    def test_debug_identity_does_not_enforce_release_native_packaging(self):
        # Debug APKs can contain uncompressed .so files. The identity test
        # must not mistake normal debug packaging for an unsafe release.
        from contextlib import redirect_stdout
        from io import StringIO
        from verify_apk_identity import main
        badging, signatures = report(signer=OTHER_SIGNER)
        identity = read_identity(badging + "application-debuggable\n", signatures)
        output = StringIO()
        args = ["verify_apk_identity.py", "--apk", "/unused/debug.apk",
                "--aapt", "/unused/aapt", "--apksigner", "/unused/apksigner",
                "--mode", "debug-isolation"]
        with (
            patch.object(sys, "argv", args),
            patch("verify_apk_identity.apk_tool_identity", return_value=identity),
            patch("verify_apk_identity.check_native_libraries",
                  side_effect=AssertionError("debug packaging must not be tested")),
            redirect_stdout(output),
        ):
            self.assertEqual(0, main())
        self.assertIn("APK_IDENTITY_PASS mode=debug-isolation", output.getvalue())
        self.assertIn("nativeLibs=not-checked", output.getvalue())

    def test_legacy_signer_and_higher_version_allow_candidate_identity(self):
        verify_policy(self.identity(), "production-update")

    def test_current_v15_version_three_is_not_a_new_release(self):
        self.assert_blocked("production-version-not-increased",
            lambda: verify_policy(self.identity(code=3, name="1.5.2"), "production-update"))

    def test_lower_code_is_not_an_update_even_with_new_name(self):
        self.assert_blocked("production-version-not-increased",
            lambda: verify_policy(self.identity(code=2, name="1.9.0"), "production-update"))

    def test_same_old_version_name_is_not_a_new_release(self):
        self.assert_blocked("production-version-name",
            lambda: verify_policy(self.identity(code=4, name="1.5.2"), "production-update"))

    def test_test_label_is_not_a_production_version_name(self):
        self.assert_blocked("production-version-name",
            lambda: verify_policy(self.identity(code=4, name="1.6.0-test"), "production-update"))

    def test_qa_signed_release_is_rejected_even_when_package_matches(self):
        # Real CI also builds an APK signed with a disposable key and
        # proves it fails this SAME production-policy branch.
        self.assert_blocked("production-signer-mismatch",
            lambda: verify_policy(self.identity(code=4, signer=OTHER_SIGNER), "production-update"))

    def test_sandbox_signed_by_historical_release_key_cannot_replace_production(self):
        self.assert_blocked("production-package-mismatch",
            lambda: verify_policy(self.identity(package=SANDBOX_PACKAGE), "production-update"))

    def test_production_package_is_not_allowed_in_sandbox(self):
        self.assert_blocked("sandbox-package-mismatch",
            lambda: verify_policy(self.identity(), "sandbox-isolation"))

    def test_isolated_debug_sandbox_is_allowed_without_claiming_update_signer(self):
        verify_policy(self.identity(
            package=SANDBOX_PACKAGE, name="1.5.2-test", code=3, signer=OTHER_SIGNER
        ), "sandbox-isolation")

    def test_sandbox_production_signer_is_rejected(self):
        self.assert_blocked("sandbox-production-signer",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.5.2-test"
            ), "sandbox-isolation"))

    def test_sandbox_must_use_test_version_suffix(self):
        self.assert_blocked("sandbox-version-name",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.6.0", signer=OTHER_SIGNER
            ), "sandbox-isolation"))

    def test_installed_test_signer_is_required_for_test_updates(self):
        self.assert_blocked("missing-test-baseline",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.6.0-test", signer=TEST_SIGNER
            ), "test-update", installed_test_code=3))

    def test_installed_test_code_is_required_for_test_updates(self):
        self.assert_blocked("missing-test-version",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.6.0-test", signer=TEST_SIGNER
            ), "test-update", expected_test_signer=TEST_SIGNER))

    def test_existing_test_signer_mismatch_is_fatal(self):
        self.assert_blocked("test-signer-mismatch",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.6.0-test", signer=OTHER_SIGNER
            ), "test-update", expected_test_signer=TEST_SIGNER, installed_test_code=3))

    def test_existing_test_version_must_increase(self):
        self.assert_blocked("test-version-not-increased",
            lambda: verify_policy(self.identity(
                package=SANDBOX_PACKAGE, name="1.6.0-test", signer=TEST_SIGNER, code=3
            ), "test-update", expected_test_signer=TEST_SIGNER, installed_test_code=3))

    def test_test_update_can_pass_with_explicit_installed_baseline(self):
        verify_policy(self.identity(
            package=SANDBOX_PACKAGE, name="1.6.0-test", signer=TEST_SIGNER, code=4
        ), "test-update", expected_test_signer=TEST_SIGNER, installed_test_code=3)

    def test_production_debuggable_apk_is_never_an_update(self):
        unsigned_metadata = self.identity()
        self.assert_blocked("production-debuggable",
            lambda: verify_policy(unsigned_metadata.__class__(
                unsigned_metadata.package, unsigned_metadata.version_code,
                unsigned_metadata.version_name, unsigned_metadata.signer_sha256,
                unsigned_metadata.v2_or_v3_signed, True
            ), "production-update"))

    def test_debug_build_with_production_package_cannot_reuse_the_production_signer(self):
        debug_identity = read_identity(
            report(signer=OTHER_SIGNER)[0] + "application-debuggable\n",
            report(signer=OTHER_SIGNER)[1]
        )
        self.assertTrue(debug_identity.debuggable)
        verify_policy(debug_identity, "debug-isolation")
        with_production_signer = read_identity(
            report()[0] + "application-debuggable\n", report()[1]
        )
        self.assert_blocked("debug-production-signer",
            lambda: verify_policy(with_production_signer, "debug-isolation"))
        self.assert_blocked("production-debuggable",
            lambda: verify_policy(with_production_signer, "production-update"))

    def test_debug_isolation_requires_debuggable_and_historical_package(self):
        self.assert_blocked("debug-flag",
            lambda: verify_policy(self.identity(signer=OTHER_SIGNER), "debug-isolation"))
        debug_sandbox = read_identity(
            report(package=SANDBOX_PACKAGE, signer=OTHER_SIGNER)[0] +
                "application-debuggable\n",
            report(signer=OTHER_SIGNER)[1]
        )
        self.assert_blocked("debug-package",
            lambda: verify_policy(debug_sandbox, "debug-isolation"))

    def test_multisigner_fails_closed(self):
        self.assert_blocked("signer-count",
            lambda: read_identity(*report(second_signer=TEST_SIGNER)))

    def test_missing_v2_or_v3_is_rejected(self):
        self.assert_blocked("signature-scheme",
            lambda: read_identity(*report(v2=False)))

    def test_unsigned_apksigner_report_rejected(self):
        self.assert_blocked("signer-count",
            lambda: read_identity(report()[0], "DOES NOT VERIFY"))

    def test_bad_aapt_manifest_rejected(self):
        self.assert_blocked("bad-manifest",
            lambda: read_identity("package: something else", report()[1]))

    def test_duplicate_manifest_declaration_rejected(self):
        self.assert_blocked("bad-manifest",
            lambda: read_identity(report()[0] * 2, report()[1]))

    def test_native_libraries_need_deflate_and_at_least_one(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "qa.apk"
            with ZipFile(file, "w") as zipf:
                zipf.writestr("lib/arm64-v8a/libnative.so", b"fake", ZIP_DEFLATED)
            self.assertEqual(1, check_native_libraries(file))
            with ZipFile(file, "w") as zipf:
                zipf.writestr("lib/arm64-v8a/libnative.so", b"fake", ZIP_STORED)
            self.assert_blocked("native-lib-packaging",
                lambda: check_native_libraries(file))
            with ZipFile(file, "w") as zipf:
                zipf.writestr("classes.dex", b"fake")
            self.assert_blocked("missing-native-libs",
                lambda: check_native_libraries(file))

    def test_android_tool_failure_must_not_fall_back_to_guessing(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "qa.apk"
            file.write_bytes(b"fake APK")
            with patch("verify_apk_identity.subprocess.run") as call:
                call.return_value = subprocess.CompletedProcess([], 1, "", "error")
                self.assert_blocked("apk-tool-failed",
                    lambda: apk_tool_identity(file, "aapt", "apksigner"))
                self.assertEqual(1, call.call_count)


if __name__ == "__main__":
    unittest.main()
