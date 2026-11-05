# Elsewhere Worlds — Encrypted Archive Sandbox UI Test Plan

**Status:** Android UI implementation in draft PR #35, stacked on the additive restore work (#34). The feature is enabled only in nonproduction preview builds; the separate `.test.worlds` application ID installs alongside the existing `.test` app. No production import UI is enabled.

## What is now connected

**Worlds → chosen World → Export encrypted** provides an export preview, a count of selected chats/messages/variants/assets, private-data flags, a separate consent checkbox when private notes/secrets/drafts are included, and twice-entered passphrase. Android's **CreateDocument** picker chooses the output. The exporter collects a *single consistent Room snapshot* and stages local images under the shared app-data maintenance lock. It checks that the full history fingerprint is unchanged since review; a change aborts the export rather than silently substituting different RP data.

**Worlds → Inspect / import** uses Android's **OpenDocument** picker. Enter a passphrase to copy the encrypted file into bounded app-private staging, authenticate/decrypt it, validate every manifest entry, ZIP hash, history graph and remapped IDs. The first step displays only read-only counts and flags. Selecting a file **never writes Room**. A different **Import NEW World** confirmation plus re-entered passphrase verifies the exact same encrypted file before the additive transaction can run. A separate imported World is created; no merging by name and no app-global API key/model setting changes.

## Offline acceptance tests

- Successful selected-World export → inspect → second-passphrase confirm → import, while the original World and its chat history remain untouched.
- Edited World/chat after preview → no export bytes written, with a human-readable reason to re-preview.
- Private unsent drafts require explicit export opt-in; preview of imported file discloses inclusion.
- Incorrect second import passphrase → no new World or message records.
- Existing #34 tests prove collision abort, actual Room rollback, orphan-image cleanup, namesake portraits and NPC variant provenance.
- New workflow assembles a separate sandbox APK `Elsewhere-Worlds-Preview-Isolated-APK` after its focused World archive tests pass.

## Device test sequence — use disposable fictional data only

1. Install **Elsewhere Worlds Preview** as a separate app. Do not replace your existing Elsewhere Test (the build with working Continue and background notifications) or production. Confirm the standard production app remains untouched.
2. Create a **throwaway World** and attach one **throwaway chat** through the Worlds UI (do not use an irreplaceable RP).
3. Add a short message, regenerate a variant, and add a small picture if supported. Save the chat and ensure no active generation is running.
4. In that World, tap **Export encrypted**. Check message, variant and image counts. Review any private-note/draft warning. Choose a unique passphrase you can remember for this disposable test and save the archive.
5. Return to Worlds and tap **Inspect / import**. Select the archive and unlock it. Check the read-only preview before agreeing to create a separate World.
6. Re-enter the passphrase and confirm. Verify that the original World and chat still exist; the newly imported World has a distinct identity but the same expected story/variant count and images.
7. Attempt an import with the wrong passphrase: nothing should be written.
8. For reinstall verification, **first** export and independently verify a throwaway World archive and keep a separate full-app safety backup. Do **not** uninstall a Test installation that holds valuable histories before export and restore have been device-verified.

## Not yet release-ready

- This is not background/foreground-service export. Keep Worlds Preview in foreground during large archive operations.
- No merge into an existing World; all identities are new.
- Unknown opaque extension schemas, unsupported NPC portrait storage and invalid co-op provenance fail closed, intentionally.
- Android process-kill, low-storage, malicious ZIP variants, massive World scale, content-provider failure and full reinstall restore remain final gates.
- Android's document provider may leave a partially written file if export fails; delete it before retrying. Never assume an incomplete file is a backup.

**Do not release the import buttons in the production build based on a single passing offline suite.**
