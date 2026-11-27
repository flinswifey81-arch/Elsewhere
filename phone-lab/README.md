# Elsewhere Phone Lab
An intentionally offline interaction prototype, not a second copy of the full RP engine. Includes synthetic Hero Society and Afterglow examples. Roleplay, API keys, import and true persistent lore or memory are not wired. Demo state is in-memory for the running Activity.
Build: ./gradlew :phone-lab:assembleDebug
Package: com.aistudio.elsewhere.abwzy.phonelab; no shared UID, INTERNET permission, or backup access. Uses Gradle's ordinary debug signer. Unless built again with the same debug key, a future APK may require reinstalling the Lab only.

## Headless systems
`systems.PhoneStore` is an Android-private SQLite store. Resolve the current World, campaign, chapter, Persona and eligible contacts from trusted story records before calling it. Contacts (lead or NPC) are keyed by stable IDs. Reads are scoped and chapter-bounded; selecting a text is explicit and never copies a full thread automatically. Story Memory projects approved text only to the participant whose perspective is being compiled, and Playthrough Lore only inside that campaign. World Canon drafts are review items, not live lore. Deleting an approval does not delete its source texts.

The current demo screens are unchanged. This branch lays the storage and validation foundations only; nothing here reads Base Elsewhere data, sends a provider request, stores an API key, installs an APK, or accepts a draft into live World Canon. Adapter work still needs to bridge trusted campaign/Persona records, the model or fake provider, backups, and the existing Story Memory/World review system.

`PulseImportStore` validates and previews v1 Feed Packs without writing; apply stores settings as World templates, and stores optional public starter state only in one explicitly reviewed campaign. It has no dependency on the phone transcript or story database. `PulseRefreshPlanner` prepares a limited request preview only from selected approved public events in that campaign. Do not wire this to background generation or include raw scenes, other campaigns, imported posts, or DMs in a request. Generating and validating actual model feed replies, safe key setup, connecting to the existing story engine, and device/backup verification are separate follow-on work.

## Local interactive build
0.2-lab replaces the original static demo with a provisional native Compose interface. It keeps
the sample Worlds, adds a sibling timeline, and connects lab-only drafts, scripted texts and explicit
carryover actions to local storage. World Pulse previews and accepts a Feed Pack from Android's system
file picker. The refresh preview does not generate feed posts. This is not the redesign or a live
engine connection. Existing Elsewhere data and provider settings remain untouched. Debug signers from
different CI runs may not match; do not uninstall production Elsewhere. If an existing 0.1 Lab blocks
installation, it held only temporary sample data, so removing the Lab alone is safe if the user chooses.
