"""Offline release-candidate safety contracts; never read signing credentials.

Tests run on PR CI before any real keystore or production-signed APK exists.
These tests lock versioning and the manual-only release workflow guardrails.
"""
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "app/build.gradle.kts"
WORKFLOW = ROOT / ".github/workflows/build-v15-release-apk.yml"
EMULATOR_WORKFLOW = ROOT / ".github/workflows/android-emulator-v15-v21-upgrade.yml"
EMULATOR_GATE = ROOT / "qa/device-upgrade/verify_disposable_upgrade.py"


class CandidateReleaseContracts(unittest.TestCase):
    def test_prepared_candidate_advances_only_installed_app_version(self):
        source = GRADLE.read_text()
        self.assertEqual(1, source.count('    versionCode = 4\n    versionName = "1.6.0"'))
        self.assertIn('val legacyApplicationId = "com.aistudio.elsewhere.abwzy"', source)
        self.assertIn('applicationIdSuffix = ".v15"', source)
        self.assertIn('keyAlias = "upload"', source)
        self.assertIn('signingConfig = signingConfigs.getByName("release")', source)

    def test_candidate_signing_is_manual_only_with_explicit_confirmation(self):
        source = WORKFLOW.read_text()
        header = source.split("\njobs:\n", 1)[0]
        self.assertIn("workflow_dispatch:", header)
        self.assertIn("confirm_candidate:", header)
        self.assertNotIn("\n  pull_request:", header)
        self.assertNotIn("\n  push:", header)
        self.assertIn('test "$GITHUB_EVENT_NAME" = "workflow_dispatch"', source)
        self.assertIn('test "$CONFIRM_CANDIDATE" = "PREPARE_1_6_0"', source)
        self.assertLess(
            source.index("Require explicit owner-requested 1.6.0 candidate build"),
            source.index("Restore v1.5 release signing key"),
        )

    def test_candidate_guard_accepts_only_confirmed_manual_main(self):
        source = WORKFLOW.read_text()
        step = source.split("      - name: Require explicit owner-requested", 1)[1]
        step = step.split("\n      - name:", 1)[0]
        script = step.split("        run: |\n", 1)[1]
        script = "\n".join(line[10:] for line in script.splitlines())
        for event, ref, confirmation, expected in (
            ("workflow_dispatch", "refs/heads/main", "PREPARE_1_6_0", 0),
            ("workflow_dispatch", "refs/tags/main", "PREPARE_1_6_0", 1),
            ("workflow_dispatch", "refs/heads/work/memory-v2-stabilization-2026-11-18", "PREPARE_1_6_0", 1),
            ("workflow_dispatch", "refs/heads/main", "wrong", 1),
            ("push", "refs/heads/main", "PREPARE_1_6_0", 1),
        ):
            with self.subTest(event=event, ref=ref, confirmation=confirmation):
                result = subprocess.run(
                    ["/bin/bash", "-c", script],
                    env={"GITHUB_EVENT_NAME": event, "GITHUB_REF": ref,
                         "CONFIRM_CANDIDATE": confirmation},
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(expected, result.returncode, result.stderr)

    def test_release_key_is_existing_and_candidate_apk_is_verified_before_artifact(self):
        source = WORKFLOW.read_text()
        self.assertIn("secrets.ELSEWHERE_V15_KEYSTORE_BASE64", source)
        self.assertIn("secrets.ELSEWHERE_V15_STORE_PASSWORD", source)
        self.assertIn("secrets.ELSEWHERE_V15_KEY_PASSWORD", source)
        self.assertIn('expected_version_code="4"', source)
        self.assertIn('expected_version_name="1.6.0"', source)
        self.assertIn('expected_legacy_signer="7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5"', source)
        self.assertIn("--mode production-update", source)
        self.assertLess(source.index("--mode production-update"),
                        source.index("actions/upload-artifact@v4"))
        self.assertIn("Elsewhere-1.6.0-Production-Signed-REVIEW-ONLY", source)
        self.assertNotIn("com.example.ProductionIdentityTest", source)
        for forbidden in ("adb install", "adb uninstall", "gh release create",
                          "softprops/action-gh-release", "actions/create-release",
                          "google-github-actions/deploy"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)

    def test_emulator_compares_real_candidate_version_without_mutating_gradle(self):
        workflow = EMULATOR_WORKFLOW.read_text()
        gate = EMULATOR_GATE.read_text()
        self.assertNotIn("EMULATOR_VERSION_ONLY", workflow)
        self.assertNotIn("original.replace(old, qa)", workflow)
        self.assertIn('source.count("    versionCode = 4\\n    versionName = \\"1.6.0\\"")', workflow)
        self.assertIn('QA_VERSION_CODE = 4', gate)
        self.assertIn('QA_VERSION_NAME = "1.6.0"', gate)
        self.assertIn("PRODUCTION_V15_SIGNER", gate)
        self.assertIn("original.signer_sha256 != candidate.signer_sha256", gate)


if __name__ == "__main__":
    unittest.main()
