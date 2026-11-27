# Elsewhere Phone Lab
An intentionally offline interaction prototype, not a second copy of the full RP engine. Includes synthetic Hero Society and Afterglow examples. Roleplay, API keys, import and true persistent lore or memory are not wired. Demo state is in-memory for the running Activity.
Build: ./gradlew :phone-lab:assembleDebug
Package: com.aistudio.elsewhere.abwzy.phonelab; no shared UID, INTERNET permission, or backup access. Uses Gradle's ordinary debug signer. Unless built again with the same debug key, a future APK may require reinstalling the Lab only.

## Headless systems
`systems.PhoneStore` is an Android-private SQLite store. Resolve the current World, campaign, chapter, Persona and eligible contacts from trusted story records before calling it. Contacts (lead or NPC) are keyed by stable IDs. Reads are scoped and chapter-bounded; selecting a text is explicit and never copies a full thread automatically. Story Memory projects approved text only to the participant whose perspective is being compiled, and Playthrough Lore only inside that campaign. World Canon drafts are review items, not live lore. Deleting an approval does not delete its source texts.

The current demo screens are unchanged. This branch lays the storage and validation foundations only; nothing here reads Base Elsewhere data, sends a provider request, stores an API key, installs an APK, or accepts a draft into live World Canon. Adapter work still needs to bridge trusted campaign/Persona records, the model or fake provider, backups, and the existing Story Memory/World review system.
