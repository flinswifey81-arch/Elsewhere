# Elsewhere 1.6.0 — production-signed release *candidate* preparation

**Status:** Prepared in draft PR #49 for verification only. **Not yet production-signed, distributed, approved or installed.** This document does not grant permission to deploy an APK. No production keystore, owner app data or device is required for normal PR CI.

## Identity and owner-confirmed provenance

The owner confirmed that the last Elsewhere APK installed on their phone was the APK from the existing historical production-signing GitHub Actions workflow. The original workflow pins the production signer certificate fingerprint to `7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5`. This confirmation makes reusing the existing workflow and signing secrets the intended path, **not** generating another key.

The phone's installed APK has **not** been independently extracted or cryptographically compared; that remains a separate last-mile check and should not be portrayed as already verified.

| Property | Installed v1.5.2 | Prepared candidate |
|---|---|---|
| Package ID | `com.aistudio.elsewhere.abwzy.v15` | **Unchanged** |
| Android versionCode | `3` | `4` |
| Display versionName | `1.5.2` | `1.6.0` |
| Room schema | `15` | `21` |
| Signature | Historical production certificate | **Must match exactly before any artifact can be accepted** |
| Owner data | On original device | **Never touched by CI** |

The `.v15` segment in the production package name is historical and intentionally retained despite changing the displayed app version.

## Automated preparation on PR #49

Ordinary PR-triggered workflows continue to use throwaway **QA/debug** credentials, synthetic Room data, a disposable Android emulator, and isolated Test app packages. The emulator rehearsal now reads the real 1.6.0/4 source version rather than rewriting it in temporary CI; a genuine historical v1.5.2/3 debug-signed APK is updated in place to the candidate debug build and original records/files must survive. No production signing secrets are accessed in these ordinary PR jobs.

The existing `.github/workflows/build-v15-release-apk.yml` is repurposed into a **manual-only candidate builder** because it is the existing workflow already wired to the historical signing secret names. The former automated `push` trigger has been removed. It refuses to run unless the owner deliberately selects PR #49's branch `work/memory-v2-stabilization-2026-11-18` and types `PREPARE_1_6_0` into the confirmation input. It runs app/backup/Worlds regression tests and the APK identity verifier before restoring the existing production keystore. A completed signed APK is then checked for:

- Exact existing package ID and prepared version 1.6.0/code 4.
- The historical production signing certificate, **not** a new/QA/debug signer.
- One valid v2/v3 APK signer, non-debuggable release variant, compressed native libraries for device packaging.
- Strictly higher versionCode than the original production v1.5.2/code 3.

Any mismatch or missing secret **fails the job**. The signed APK is available only as a GitHub Actions **30-day review artifact** after all checks pass. The private signing keystore is deleted from the runner on success or failure. No workflow step installs an APK, performs a GitHub Release, merges the PR or deploys to an app store.

## When the owner chooses to create the signed candidate

**Do not trigger this manual builder until draft PR #49's eight applicable CI workflows all pass for the exact current SHA and its source changes are reviewed.** It is not automatically triggered by edits or ordinary PR verification.

In GitHub Actions, select **Prepare Elsewhere 1.6.0 Signed Candidate (manual, no install)**, choose branch `work/memory-v2-stabilization-2026-11-18`, and type `PREPARE_1_6_0`. Do not use another branch. GitHub's existing `ELSEWHERE_V15_*` secrets must still be configured; their presence and usability have not been independently confirmed by ordinary PR CI. If the job reports missing or incompatible credentials, **stop** and recover the original key under the owner's control—never substitute a freshly generated key.

When the manual job succeeds, review the artifact's attached metadata and the finished APK certificate with the frozen historical identity. The artifact is a *candidate*, not an instruction to install.

## Separate release-acceptance blockers

1. Confirm backup/recovery safety with the owner and check encrypted backup files/passphrases **privately**, never in chat or CI. Protect original app data before any upgrade.
2. If needed for extra assurance, run the already prepared read-only local installed-APK verification on the owner's own trusted PC. The owner has confirmed the source workflow but the phone's installed signer has not been directly checked.
3. Review actual application feature acceptance, not just test coverage, and obtain the owner's approval for the release.
4. Perform any planned real-device trial on an appropriately isolated disposable device before considering installation over the owner's only production copy.

**Release decision is still BLOCKED.** A green PR build and green synthetic upgrade prove the tested code paths, not that all promised features are complete or that the owner's real data is recoverable.
