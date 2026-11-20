"""Fail-closed tests for APK identity parser and the release-vs-QA boundary."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_apk_identity import ApkIdentity, evaluate, parse_apk_identity


BASELINE = {
    "historical_commit": "b2295d69877ba003e6473c8a40e1f7a514c560f7",
    "historical_version_code": 3,
    "historical_version_name": "1.5.2",
    "production_package": "com.aistudio.elsewhere.abwzy.v15",
    "historical_production_signer_sha256": "7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5",
}
QA_SIGNER = "a" * 64
PROD_SIGNER = BASELINE["historical_production_signer_sha256"]
PKG = BASELINE["production_package"]


def apk(signer=QA_SIGNER, code=3, package=PKG, name="1.5.2"):
    return ApkIdentity(package, code, name, signer, True)


BADGING = "package: name='com.aistudio.elsewhere.abwzy.v15' versionCode='3' versionName='1.5.2'\n"
VERIFIED = (
    "Verifies\n"
    "Verified using v1 scheme (JAR signing): false\n"
    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
    "Verified using v3 scheme (APK Signature Scheme v3): false\n"
    "Number of signers: 1\n"
    "Signer #1 certificate SHA-256 digest: " + QA_SIGNER + "\n"
)


class IdentityGuardTests(unittest.TestCase):
    def test_reads_actual_badging_and_v2_signer(self):
        self.assertEqual(apk(), parse_apk_identity(BADGING, VERIFIED))

    def test_badging_with_missing_version_or_multiple_packages_fails_closed(self):
        for text in (
            BADGING.replace("versionCode='3'", ""),
            BADGING + BADGING,
            BADGING.replace("versionCode='3'", "versionCode='3x'"),
        ):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    parse_apk_identity(text, VERIFIED)

    def test_missing_modern_signature_or_multiple_signers_fails_closed(self):
        for report in (
            VERIFIED.replace("scheme v2): true", "scheme v2): false"),
            VERIFIED.replace("Number of signers: 1", "Number of signers: 2") +
                "Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n",
            VERIFIED.replace("Signer #1 certificate SHA-256 digest", "Signer #1 certificate SHA-1 digest"),
        ):
            with self.subTest(report=report):
                with self.assertRaises(ValueError):
                    parse_apk_identity(BADGING, report)

    def test_same_ephemeral_qa_key_confirms_only_qa_compatibility(self):
        report = evaluate(BASELINE, apk(), "qa", historical=apk())
        self.assertEqual(report["status"], "QA_CHAIN_VERIFIED_PRODUCTION_RELEASE_BLOCKED")
        self.assertTrue(report["samePackage"])
        self.assertTrue(report["historicalAndCandidateShareDisposableQaSigner"])
        self.assertFalse(report["versionCodeIncreases"])
        self.assertFalse(report["usesPinnedProductionSigner"])
        self.assertFalse(report["installedApkSignerConfirmed"])
        self.assertFalse(report["eligibleForInPlaceProductionUpdate"])
        self.assertTrue(any("versionCode" in reason for reason in report["remainingBlockers"]))

    def test_even_bumped_version_cannot_turn_qa_key_into_production_key(self):
        report = evaluate(BASELINE, apk(code=4, name="1.6.0"), "qa", historical=apk())
        self.assertTrue(report["versionCodeIncreases"])
        self.assertFalse(report["eligibleForInPlaceProductionUpdate"])

    def test_qa_mode_rejects_certificate_drift_or_accidental_production_key(self):
        for candidate, historical in (
            (apk(signer="b" * 64), apk()),
            (apk(signer=PROD_SIGNER), apk(signer=PROD_SIGNER)),
            (apk(), apk(package="com.example.other")),
            (apk(), apk(code=2)),
        ):
            with self.subTest(candidate=candidate, historical=historical):
                with self.assertRaises(ValueError):
                    evaluate(BASELINE, candidate, "qa", historical=historical)

    def test_debuggable_release_and_stale_version_name_are_never_approved(self):
        flagged = parse_apk_identity(BADGING + "application-debuggable\\n", VERIFIED)
        self.assertTrue(flagged.debuggable)
        with self.assertRaises(ValueError):
            evaluate(BASELINE, flagged, "qa", historical=apk())
        for candidate in (
            apk(signer=PROD_SIGNER, code=4, name="1.5.2"),
            apk(signer=PROD_SIGNER, code=4, name="1.6.0-test"),
            ApkIdentity(PKG, 4, "1.6.0", PROD_SIGNER, True, True),
        ):
            with self.subTest(candidate=candidate):
                with self.assertRaises(ValueError):
                    evaluate(BASELINE, candidate, "production")

    def test_production_gate_requires_matching_signer_and_version_increment(self):
        for candidate in (apk(signer=QA_SIGNER, code=4), apk(signer=PROD_SIGNER, code=3)):
            with self.subTest(candidate=candidate):
                with self.assertRaises(ValueError):
                    evaluate(BASELINE, candidate, "production")
        report = evaluate(BASELINE, apk(signer=PROD_SIGNER, code=4, name="1.6.0"), "production")
        self.assertTrue(report["eligibleForInPlaceProductionUpdate"])
        self.assertFalse(report["installedApkSignerConfirmed"])

    def test_missing_baseline_fields_or_invalid_signer_or_package_fail_closed(self):
        for baseline, candidate in (
            ({"production_package": PKG}, apk()),
            ({**BASELINE, "historical_production_signer_sha256": "bad"}, apk()),
            (BASELINE, apk(package="com.example.unrelated")),
        ):
            with self.subTest(baseline=baseline):
                with self.assertRaises(ValueError):
                    evaluate(baseline, candidate, "qa", historical=apk())


if __name__ == "__main__":
    unittest.main(verbosity=2)
