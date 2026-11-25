# Native Elsewhere 1.5.2 (Room v15) encrypted-backup restore acceptance

**Status:** CI-driven **synthetic compatibility gate**, not production approval.  
**PR:** #49 (draft; preserve installed Elsewhere).  
**Historical v15 source:** `b2295d69877ba003e6473c8a40e1f7a514c560f7` — pinned original 1.5.2 production-candidate commit.

## What actually runs

The CI runners use two separate source checkouts:

1. Build a full SQLite schema with **real historical v15 Room** (`FullV15RoomFixtureExportTest`), with selected/discarded message variants, Wack, legacy memories/summaries, user draft, linked World/Lorebook, NPC, Scenario, co-op and attachment metadata. Independently check the native database, then migrate with v21's original six migrations and verify all original table-row digests plus an interrupted transactional migration rollback/retry.
2. Compile `OriginalV15EncryptedBackupExportTest` against the **v15 production-application-ID debug build**. It seeds **only synthetic data**, including a genuine 1×1 PNG image attachment and Character avatar, and calls the historical `BackupRepository.exportEncrypted` to create **v15's actual encrypted archive format**. The archive and test-only passphrase are present only on the disposable CI runner, not checked into Git, uploaded as a CI artifact, or shared with a model provider.
3. With an independent Room v21 sandbox application and **the exact ciphertext bytes exported in step 2**, execute `NativeV15EncryptedRestoreAcceptanceTest`:
   - Inspect archive and cryptographic SHA-256 digest; require declared database version 15, expected counts, and images
   - Restore with verified digest and check all selected/alternate messages, Wack marker, unsent draft, old summary/memory, Lorebook, World, NPC and actual PNG/avatar bytes
   - Assert that migrating this historical archive invents no approved Story Memory, character-knowledge grant, perspective, or Wack return
   - Wrong password, tampered ciphertext, or a changed-after-inspection hash must fail **before any data replacement**; previous synthetic transcript, image/avatar, and summary must remain intact
   - Inject failure after database replacement and after profile-image replacement; in both cases assert **automatic exact private safety-backup recovery**, preservation of the old transcript/summary/attachment/avatar, successful explicit `recoverLastRestore`, and original encrypted archive digest unchanged

The dedicated `.github/workflows/native-v15-v21-upgrade.yml` and the `native-v15-to-v21` job in `.github/workflows/memory-v2-verification.yml` both require **four restored-archive acceptance cases** as well as the two migration tests, and **reject skipped test cases**. The ordinary Memory V2 checks remain separate.

## Trust boundaries and release blockers

This is **not** an actual customer/device backup. It uses only synthetic records, a publicly non-secret dummy passphrase, local test image bytes, and ephemeral files. The tests exercise the backup crypto, serializer, validation, application-ID policy, RestoreSnapshot, SQLite database and file recovery code under Robolectric, not actual hardware keystore or an installed app.

A successful run is not proof of a real owner's private backup or device. It does not authorize changing the existing production install, obtaining real private backups for CI, replacing a signing key, changing package ID, or merging/releasing PR #49.

**Before a real upgrade:** keep two protected external copies of the user's real encrypted v15 export, locally verify digest and passphrase with the user present, check original and candidate APK package names and signer certificate fingerprints, perform real-format restore and image inspection on a **separately installed/isolated correctly signed disposable QA device** with no production data, demonstrate rollback and protected data recovery, and obtain explicit owner approval. Never place user content, recovery archives, passphrases or API keys in public CI logs or GitHub artifacts.

The original SQL Room migration checks and original 1.5.2 archive restore checks are **separate**. Neither substitutes for the other.
