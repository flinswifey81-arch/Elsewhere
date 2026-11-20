#!/usr/bin/env python3
"""Standalone no-Android regression checks for the fail-closed APK identity gate."""
import unittest

from apk_identity_gate import (
    ApkIdentity,
    IdentityFailure,
    PRODUCTION_CERT_SHA256,
    PRODUCTION_PACKAGE,
    SANDBOX_PACKAGE,
    WORLDS_PREVIEW_PACKAGE,
    check_identity,
    parse_apk_tool_outputs,
)


OTHER_SIGNER = "a" * 64
THIRD_SIGNER = "b" * 64


def identity(package=PRODUCTION_PACKAGE, code=3, name="1.5.2",
             signer=OTHER_SIGNER, debuggable=False):
    return ApkIdentity(package, code, name, signer, debuggable)


class ApkIdentityGateTest(unittest.TestCase):
    def test_real_apk_badging_and_apksigner_outputs_parsed(self):
        badging = (
            "package: name='com.aistudio.elsewhere.abwzy.v15' "
            "versionCode='4' versionName='1.6.0' compileSdkVersion='36'\n"
            "sdkVersion:'33'\napplication-label:'Elsewhere v1.5'\n"
        )
        signer = (
            "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
            "Signer #1 certificate SHA-256 digest: " + PRODUCTION_CERT_SHA256.upper() + "\n"
        )
        parsed = parse_apk_tool_outputs(badging, signer)
        self.assertEqual(PRODUCTION_PACKAGE, parsed.package)
        self.assertEqual(4, parsed.version_code)
        self.assertEqual("1.6.0", parsed.version_name)
        self.assertEqual(PRODUCTION_CERT_SHA256, parsed.signer_sha256)
        self.assertFalse(parsed.debuggable)

    def test_missing_badging_rejected(self):
        with self.assertRaisesRegex(IdentityFailure, "BADGING_MISSING"):
            parse_apk_tool_outputs("unknown", "Signer #1 certificate SHA-256 digest: " + OTHER_SIGNER)

    def test_missing_or_multiple_signers_rejected(self):
        badging = "package: name='com.aistudio.elsewhere.abwzy.v15' versionCode='4' versionName='1.6.0'"
        with self.assertRaisesRegex(IdentityFailure, "SIGNER_COUNT_INVALID"):
            parse_apk_tool_outputs(badging, "")
        with self.assertRaisesRegex(IdentityFailure, "SIGNER_COUNT_INVALID"):
            parse_apk_tool_outputs(
                badging,
                "Signer #1 certificate SHA-256 digest: " + OTHER_SIGNER + "\n"
                "Signer #2 certificate SHA-256 digest: " + THIRD_SIGNER,
            )

    def test_wrong_package_never_upgrade_approved(self):
        with self.assertRaisesRegex(IdentityFailure, "PACKAGE_MISMATCH"):
            check_identity(identity(package=SANDBOX_PACKAGE, code=4,
                name="1.6.0", signer=PRODUCTION_CERT_SHA256), "production")

    def test_wrong_certificate_blocks_production_even_with_higher_version(self):
        with self.assertRaisesRegex(IdentityFailure, "SIGNER_MISMATCH"):
            check_identity(identity(code=4, name="1.6.0"), "production")

    def test_current_version_is_not_a_new_release_even_when_signed_correctly(self):
        with self.assertRaisesRegex(IdentityFailure, "VERSION_NOT_UPGRADED"):
            check_identity(identity(signer=PRODUCTION_CERT_SHA256), "production")

    def test_stale_version_name_blocks_production_even_when_code_is_higher(self):
        with self.assertRaisesRegex(IdentityFailure, "VERSION_NAME_STALE"):
            check_identity(identity(code=4, signer=PRODUCTION_CERT_SHA256), "production")

    def test_valid_production_requires_all_three_conditions(self):
        check_identity(identity(code=4, name="1.6.0", signer=PRODUCTION_CERT_SHA256),
                       "production")

    def test_debuggable_release_rejected_despite_correct_signer(self):
        with self.assertRaisesRegex(IdentityFailure, "RELEASE_DEBUGGABLE"):
            check_identity(identity(code=4, name="1.6.0",
                signer=PRODUCTION_CERT_SHA256, debuggable=True), "production")

    def test_qa_release_accepts_only_explicit_nonproduction_key(self):
        check_identity(identity(), "qa-release", OTHER_SIGNER)
        with self.assertRaisesRegex(IdentityFailure, "QA_SIGNER_MISMATCH"):
            check_identity(identity(), "qa-release", THIRD_SIGNER)
        with self.assertRaisesRegex(IdentityFailure, "QA_SIGNER_COLLIDES_WITH_PRODUCTION"):
            check_identity(identity(), "qa-release", PRODUCTION_CERT_SHA256)

    def test_debug_same_package_must_not_be_signed_as_production(self):
        check_identity(identity(), "debug")
        with self.assertRaisesRegex(IdentityFailure, "TEST_SIGNER_IS_PRODUCTION"):
            check_identity(identity(signer=PRODUCTION_CERT_SHA256), "debug")

    def test_sandbox_and_worlds_preview_must_be_distinct_from_production(self):
        check_identity(identity(package=SANDBOX_PACKAGE), "sandbox")
        check_identity(identity(package=WORLDS_PREVIEW_PACKAGE), "worlds-preview")
        with self.assertRaisesRegex(IdentityFailure, "PACKAGE_MISMATCH"):
            check_identity(identity(package=PRODUCTION_PACKAGE), "sandbox")
        with self.assertRaisesRegex(IdentityFailure, "TEST_SIGNER_IS_PRODUCTION"):
            check_identity(identity(package=SANDBOX_PACKAGE,
                signer=PRODUCTION_CERT_SHA256), "sandbox")


if __name__ == "__main__":
    unittest.main()
