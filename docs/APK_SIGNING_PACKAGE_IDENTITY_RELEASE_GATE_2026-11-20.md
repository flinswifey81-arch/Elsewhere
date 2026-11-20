# Elsewhere APK package/signature identity — pre-release safety gate

**Status:** draft automated **identity/isolation** QA. This is not approval to sign, install, upgrade, or merge a production APK.  
**Review:** draft PR #49.  
**Verifier:** `qa/apk-identity/verify_apk_identity.py`.  
**CI:** `.github/workflows/apk-identity-safety.yml`.

## Historical baseline pinned in the original v1.5.2 production workflow

| Item | Value |
|---|---|
| Production application ID | `com.aistudio.elsewhere.abwzy.v15` |
| Historical production version | `1.5.2`, `versionCode=3` |
| Historical production signing certificate SHA-256 | `7c1fa02f0732d179d509d5333afa40933743e3c5dc8b70ef0aff2607c1e759f5` |
| Isolated Elsewhere Test application ID | `com.aistudio.elsewhere.abwzy.test` |
| Historical Test installed signature/code | **Unknown until independently read from the actual installed Test APK** |

The pinned fingerprint comes from `.github/workflows/build-v15-release-apk.yml`, which labels it as checked against previous v1.5/v1.5.1 APKs. This PR **has not independently extracted the certificate from the owner's installed APK**; compare that fingerprint against the known-good actual installed APK/device certificate before a production install.

## Two different gates

**1. CI-only identity isolation proof (runs automatically on PR changes)**

The workflow separately checks out the **pinned historical 1.5.2 release workflow** and verifies the frozen package, version, and certificate fingerprint against that source; nobody may silently redefine the accepted signer. It runs actual Android **sandbox and debug APKs** through `aapt` and `apksigner`. Sandbox must be the isolated `.test` app with a `-test` version and non-production signer. Debug uses the **same package ID as production** in the current Gradle settings, but must be both visibly debuggable and signed by a certificate **different from production**. Neither should ever be installed over the owner's live application. The workflow also verifies all native `.so` libraries are compressed for the existing release packaging policy.

The workflow also creates an **actual production-shaped APK using a throwaway QA keystore**. It checks that the APK's certificate matches the newly generated disposable key, and that this certificate does **not** match the historical production signer. That APK must be REJECTED by the verifier, **specifically** for `production-signer-mismatch`. This ensures a release-looking filename, manifest and valid signature do not trick the gate into declaring it an update to the user's app. These synthetic APKs and the QA keystore are deleted from the runner and never uploaded as build artifacts, installed, distributed or signed with historical secrets.

**2. True release-candidate verification (not yet run on the user's real release signer)**

`verify_apk_identity.py --mode production-update` must read the *actual finished candidate APK*, not trust Gradle source declarations, and enforce:
- Exact production application ID
- A valid, single-signer v2/v3 APK signing certificate matching the **pinned v15 certificate SHA-256**, with no debuggable flag
- Strictly increasing `versionCode`: **greater than 3** for a v15 production upgrade
- A new non-Test `versionName` (not `1.5.2`)
- Actual native-library packaging policy

The current development Gradle configuration still advertises **`versionCode=3`, `versionName=1.5.2`**. Do **not** call it an upgrade-ready release without deliberately setting a new version and validating it. The old manually triggered `build-v15-release-apk.yml` is a *historical v15 candidate* workflow; it should not be used as proof that the new draft v21 code has been production-signed or upgraded.

For a signed **Elsewhere Test upgrade**, `--mode test-update` additionally requires the *actual installed Test signer fingerprint* via `--expected-test-signer` and its installed code via `--installed-test-code`. If they are unknown, the tool refuses to promise the Test APK can update the installed copy. The sandbox isolation check does **not** substitute for Test-update compatibility.

## What this cannot prove

The QA signer cannot establish that the production key is available, matches the user's real installed certificate, or that any given candidate has the correct certificate lineage. The read-only APK test does not exercise Android PackageManager's update transaction, file/data retention, device ABI, user permissions, attached image storage, recovery after installation failure, or the user's real v15 encrypted backups.

**Physical-device release gate is still BLOCKED:** Obtain/confirm the historical certificate and exact package/version **locally from the existing owner-installed APK**, keep protected backups and signer credentials out of GitHub PRs, build the candidate using a verified correct signing key, compare finished APK certificates and version to original, then test an update on a **separately provisioned disposable signed QA device** with rollback and decrypted data verification. Do not install over the only production app or clear its data without explicit owner approval. No Google Play upload, installation or signing-secret access is part of this PR gate.
