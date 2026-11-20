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

**1. CI-only identity isolation proof (runs automatically on APK-affecting PR changes)**

The trigger covers app source/resources/manifests, app and root Gradle settings, build logic, version catalogs, and the verifier itself. Only the newest in-progress run of this workflow for the PR is retained by its concurrency policy; cancelled older runs are not accepted as verification evidence. The two-APK comparison additionally labels a candidate whose versionCode is not greater than the historical version **not upgrade-ready**, even if its package ID and temporary QA signing identity match.

The workflow separately checks out the **pinned historical 1.5.2 release workflow** and verifies the frozen package, version, and certificate fingerprint against that source; nobody may silently redefine the accepted signer. It builds the **pinned historical v1.5.2 production-shaped APK** and the **current draft production-shaped APK**, both using the same disposable QA signing key. It compares their actual manifest package IDs and certificate identities, checks the frozen historical version, and explicitly refuses to treat their matching throwaway signer as a valid production update. It also runs actual Android **sandbox and debug APKs** through `aapt` and `apksigner`. Sandbox must be the isolated `.test` app with a `-test` version and non-production signer. Debug uses the **same package ID as production** in the current Gradle settings, but must be both visibly debuggable and signed by a certificate **different from production**. Neither should ever be installed over the owner's live application. The workflow verifies that **release-shaped and sandbox APKs** package native `.so` libraries according to the existing release policy. A **debug APK** is checked for package, debug flag, and signer isolation only: its default native-library ZIP compression can differ by design and is not evidence about production packaging. Similarly, an installed base APK may put native libraries in split APKs, so the installed-baseline mode checks identity without inspecting native-library ZIP entries.

The workflow also creates an **actual production-shaped APK using a throwaway QA keystore**. It checks that the APK's certificate matches the newly generated disposable key, and that this certificate does **not** match the historical production signer. That APK must be REJECTED by the verifier, **specifically** for `production-signer-mismatch`. This ensures a release-looking filename, manifest and valid signature do not trick the gate into declaring it an update to the user's app. These QA-built APKs and the QA keystore are deleted from the runner and never uploaded as build artifacts, installed, distributed or signed with historical secrets.

**2. True release-candidate verification (not yet run on the user's real release signer)**

`verify_apk_identity.py --mode production-update` must read the *actual finished candidate APK*, not trust Gradle source declarations, and enforce:
- Exact production application ID
- A valid, single-signer v2/v3 APK signing certificate matching the **pinned v15 certificate SHA-256**, with no debuggable flag
- Strictly increasing `versionCode`: **greater than 3** for a v15 production upgrade
- A new non-Test `versionName` (not `1.5.2`)
- Actual native-library packaging policy

The current development Gradle configuration still advertises **`versionCode=3`, `versionName=1.5.2`**. Do **not** call it an upgrade-ready release without deliberately setting a new version and validating it. The old manually triggered `build-v15-release-apk.yml` is a *historical v15 candidate* workflow; it should not be used as proof that the new draft v21 code has been production-signed or upgraded.

For a signed **Elsewhere Test upgrade**, `--mode test-update` additionally requires the *actual installed Test signer fingerprint* via `--expected-test-signer` and its installed code via `--installed-test-code`. If they are unknown, the tool refuses to promise the Test APK can update the installed copy. The sandbox isolation check does **not** substitute for Test-update compatibility.

## Read-only installed v1.5.2 identity confirmation (owner-controlled, not yet performed)

The historical certificate fingerprint above is **source-workflow evidence**, not evidence from the phone. The verifier now supports `--mode installed-baseline` to check an APK copied locally from the **existing installed production package**, without signing or installing anything. It checks the actual APK's valid v2/v3 signature, original package ID, version code 3/name 1.5.2, non-debuggable flag, and the pinned historical certificate. It **fails closed** on any mismatch; do not automatically change the fingerprint or version expectations to make it pass.

When the owner is ready and has a private backup, the release operator may perform this **locally**, with the owner's knowledge, using installed Android platform tools:

```sh
adb devices
adb shell pm path com.aistudio.elsewhere.abwzy.v15
# Copy the exact path to BASE.APK from the output above. No uninstall or install.
adb pull "/path/from/pm/output/base.apk" "./installed-elsewhere-v15-base.apk"
python3 qa/apk-identity/verify_apk_identity.py \
  --apk "./installed-elsewhere-v15-base.apk" \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --mode installed-baseline
```

These commands are an **operator recipe, not a request to run them now**. The `adb pull` path is an example placeholder, not an actual path on the user's phone. Device authorization and Android SDK paths differ by computer. `pm path` may list both base and split APKs; the base APK is adequate to inspect signing/package identity, and native library packaging is deliberately **not** checked in this mode because native libraries may live in separate splits. If the base APK is inaccessible, stop; do not root the device, uninstall anything, clear data, or substitute a different file.

Do not attach the APK to a chat, ticket, public Actions log, GitHub PR, or artifact. Inspect only on the owner's own computer, share only the non-secret verification result if desired, and delete the temporary local copy afterward. The tool does **not** retrieve a production signing key or access chat/backup data. A matching identity makes further evaluation possible; it still does not prove data migration, installation success, or recoverability.

## First live CI finding

An initial signing QA run on older commit `10dd197` built its APKs and passed sandbox identity isolation, but failed when the **debug** APK's native-library compression was incorrectly tested against the release policy (`native-lib-packaging`). The verifier was corrected on the draft branch to apply that packaging check only to release-shaped and sandbox builds. A dedicated regression test was added. **The corrected current-head run must pass before marking the gate verified**; the older failure is not evidence of a production APK signing mismatch.

## What this cannot prove

The QA signer cannot establish that the production key is available, matches the user's real installed certificate, or that any given candidate has the correct certificate lineage. The read-only APK test does not exercise Android PackageManager's update transaction, file/data retention, device ABI, user permissions, attached image storage, recovery after installation failure, or the user's real v15 encrypted backups.

**CI verification status must be checked at the current PR head:** the two-APK comparison is implemented but must not be described as passing until its GitHub Actions run completes. The historical source checkout is pinned at `b2295d69877ba003e6473c8a40e1f7a514c560f7`.

**Physical-device release gate is still BLOCKED:** Obtain/confirm the historical certificate and exact package/version **locally from the existing owner-installed APK**, keep protected backups and signer credentials out of GitHub PRs, build the candidate using a verified correct signing key, compare finished APK certificates and version to original, then test an update on a **separately provisioned disposable signed QA device** with rollback and decrypted data verification. Do not install over the only production app or clear its data without explicit owner approval. No Google Play upload, installation or signing-secret access is part of this PR gate.
