# Elsewhere — package identity, signing certificate, and safe pre-install gate

**Status:** Automated *disposable QA* only. Not a production signer attestation or authorization to install/merge.  
**PR:** #49, `work/memory-v2-stabilization-2026-11-18` (draft).  
**Frozen v15 baseline:** commit `b2295d69877ba003e6473c8a40e1f7a514c560f7`, the 1.5.2 production-candidate release workflow.

## Immutable installed-production baseline

| Fact | Historical 1.5.2 value |
|---|---|
| Production package/application ID | `com.aistudio.elsewhere.abwzy.v15` |
| Historical version | `1.5.2`, `versionCode = 3` |
| Historical signing certificate SHA-256 pin | `7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5` |
| Isolated sandbox/test package | `com.aistudio.elsewhere.abwzy.test` |
| Historical Worlds Preview package | `com.aistudio.elsewhere.abwzy.v15.test.worlds` |

**Important:** Current `debug` and `release` use the **same production package ID**, but the normal debug APK is **not signed by the production certificate**. A debug APK is *not* a legitimate in-place update, and must never prompt the owner to uninstall the production app to install it. The separate **sandbox** package exists for disposable development/QA.

## New enforced CI evidence

`.github/workflows/apk-identity-safety-gate.yml` runs on PR changes to build/source/config and on explicit dispatch, with **no real production signing secrets and no installation**:

1. Read the original historical 1.5.2 signed-release workflow from its pinned Git commit, then independently compare its legacy package, version, and signing-certificate values to the QA policy.
2. Run pure Python positive/negative tests of the production package, certificate, single signer, debuggable flag and strict version-bump conditions (`qa/release/test_apk_identity_gate.py`).
3. Generate an **ephemeral** QA signing key on the runner; build actual Android `release`, `sandbox`, and `debug` APK variants without installing any. Nothing is uploaded as an artifact.
4. Run Android SDK `aapt` to identify the package ID/version and `apksigner verify --verbose --print-certs` to verify each APK's actual certificate, through `qa/release/apk_identity_gate.py`.
5. Require a production-shaped, release-mode APK signed with the disposable QA key to **PASS QA identity inspection** and **FAIL production-upgrade approval specifically because the signer differs** from the historical v15 certificate. Confirm sandbox and debug APKs have the expected identity and cannot carry the real production signer.
6. Remove the disposable key and generated APKs from the runner even on failed jobs. **No `upload-artifact` step, `adb install`, `adb uninstall`, or production deployment**.

`--mode production` is intentionally stricter than compatibility alone: it requires the **historical production signer**, the exact production package ID, a non-debuggable release build, a **strictly larger `versionCode` than 3**, and a new version name. The current draft still declares `1.5.2 (3)`, so it is **not** an approved production upgrade even if somebody obtains a compatible signing key. Version bump and intentional release policy review are **future** release steps, not shortcuts taken in the Memory V2 branch.

A successful disposable QA-signer build proves that the checker *rejects incompatible signers* and that the signed APK tooling works. **It does not prove the owner's original signing keystore exists, unlocks, or matches the installed APK.** That stronger proof is deliberately withheld until the owner authorizes a controlled signing/production preflight.

## Remaining manual / on-device requirements

Before anyone installs over the owner's sole Elsewhere production copy:

- Confirm the package identity and actual SHA-256 certificate fingerprint from a **known-good installed v15 APK**, or from a locally retained trusted v15 signed APK. The historical workflow fingerprint is an expected baseline, **not a cryptographic reading from the user's specific phone**.
- Confirm the candidate APK's package matches exactly and `apksigner verify --print-certs` reports that same certificate. Confirm the candidate versionCode increases from the installed app, and the APK is a real non-debuggable release. Do not distribute or attempt to install a test-signed production-package APK.
- Keep independent externally protected copies of the original v15 encrypted backup, verify cryptographic digest/passphrase **offline** and test a recovery on a disposable, correctly signed environment; never upload real backups or passphrases to GitHub.
- Test the production migration, attachment images, rollback and signing compatibility on a disposable device with a rehearsed recovery procedure. This includes Android signing identity checks—not just repository settings.
- Obtain explicit owner approval before creating/distributing a production-signed candidate, installing, uninstalling, changing signing material, merging the PR or accessing private backup files.

**Release gate stays BLOCKED** until on-device signer, backup, and rollback proof is separately recorded. See `docs/NATIVE_V15_ENCRYPTED_BACKUP_RECOVERY_QA_2026-11-20.md` and `docs/STAGE1_V16_SAFE_UPGRADE_RELEASE_GATE.md`.
