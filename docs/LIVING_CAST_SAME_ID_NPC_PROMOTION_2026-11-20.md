# Elsewhere — Living Cast, NPC → Character promotion (2026-11-20)

**Status:** Implemented in draft PR #49; exact-head CI and physical-device acceptance still required. **Not a release.** This is the first slice of Stage 4 of the finalized 2026-11-09 Memory-First Roadmap; it does **not** mark the whole stage complete.

## User workflow

In a chat's NPC Bank, the player selects **Make Character from NPC**, reads the warning, then explicitly confirms **Create linked Character**. The resulting Character has the **same stable ID** as the NPC dossier. The original NPC remains in its Bank, with its scene presence, selected message-variant update history, and local relationship/knowledge context intact. Repeating the action is idempotent, not a second card.

For Solo and Group chats the linked Character is added as another participant without replacing the original lead, changing the chat type, or erasing messages. In a Solo chat, **Character spotlight** appears when more than one active Character is present; selecting another Character updates persisted participant ordering. The *next new* model reply is generated using the chosen Character. Switching back is supported. Group chats already provide selectable Character responders. Scenario sessions still use their narrator; their linked Character is available in the library and can be referenced through the Scenario NPC Character link instead.

A linked NPC and Character with the same ID are **one identity**, not two supporting speakers. When the Character is responding, the Scene Cast and NPC context exclude the same NPC ID so the model is not told that the same person is separately present as a second NPC.

## Privacy and scope

- The Character card's reusable baseline includes **name, aliases, role, appearance, personality and goals** from the current selected-variant NPC projection after the chat's Wack boundary.
- NPC **secrets, private author notes, individual Character knowledge and specific Persona relationships** remain inside the original chat's NPC Bank; they are **not** exported as global Character knowledge. User can edit the resulting Character card deliberately afterward.
- Stable origin is marked by `_elsewhere_promoted_npc_id` in the Character card's extension fields; confirmed same-ID JSON updates preserve this **trusted, preexisting** origin even if the imported file omits it. Imported new cards/copies cannot forge the app-owned origin marker.
- When the promoted Character responds, the NPC's **relationship with the active Persona** may be included dynamically from *that chat only*, without writing it into the global Character card. This context never includes NPC secrets, author notes or relationships with other Personas. It does not automatically reintroduce an old relationship after Wack.
- A different Character already owning the exact ID causes promotion to fail closed. No matching by display name and no automatic cross-chat merging.
- If an NPC portrait exists, the app attempts an atomic **copy** to the Character profile image namespace; it never deletes or moves the original NPC portrait. No OpenRouter/model calls.
- Existing saved messages, avatar records, Story Memory V2, legacy roleplay memories, NPC updates, selected variants, attached World membership and chat settings remain in place. Solo spotlight never rewrites prior speaker snapshots.

## Tests/gates

`LivingCastPromotionTest` tests Solo identity retention, memory, Wack-independent stored state, private data exclusions, idempotency, same-ID JSON overwrite, Solo switch/return, Group roster, Scenario narrator isolation, cross-chat collision rejection, and source-avatar retention.

`SoloSpotlightControlsTest` tests the real Compose selector and disables switching during generation.

`ChatDetailGenerationTest.promotedNpcSpotlightSwitchesRealProviderResponderWithoutRestartingStory` verifies that the actual model request uses the newly promoted Character, excludes NPC private author notes/relationships/secrets, keeps earlier RP turns, and returns to the original speaker without creating a new chat or making extra model calls.

Both the integrated Worlds/RP workflow and manual-only release build gate require these tests.

## Existing Character → independent chat NPC role

The NPC Bank now offers **Add existing Character as NPC**, with a searchable list of saved Characters. This creates one chat-local dossier with its **own NPC ID** and an explicit internal `_elsewhere_linked_character_id` pointing to the existing global Character ID. A Character may appear in several unrelated stories; each gets a different NPC dossier, presence, relationship, discoveries and variant-update history. The Character card itself is not cloned, reimported or overwritten. Repeated selection in the same chat is idempotent.

For Solo and Group chats, the same existing Character is also added to the selectable Character roster **at the end**, keeping the established lead. The player chooses when to change spotlight; Scenario narration remains Narrator-led. When a linked Character is answering, its NPC identity is excluded from the supporting Scene Cast to prevent double-speaking. The Character's authored personality, voice, behavior, identity, appearance and backstory can inform supporting NPC portrayal, but the compiler never pulls that card's other chat memories, `knowledgeJson`, creator-only `authorNotesJson` or unrelated World context into the NPC narrator block. Chat-local NPC fields remain editable and take contextual precedence.

The typed Character link remains only in local NPC dossier metadata and encrypted app data. Ordinary NPC JSON exports (even full-private) and reusable NPC Library copies **strip** this internal app link; untrusted JSON imports cannot forge it. Exporting/importing a standalone NPC definition on another device does not automatically attach it to a coincidentally matching Character.

`CharacterToNpcLinkTest` verifies one Character used as a supporting NPC in separate chats without merging local relationships, linked portrayal in the prompt, private-notes/knowledge exclusion, distinct NPC IDs, stable chat history, Solo spotlight eligibility, and inability to forge links through JSON or NPC library import. `ChatDetailGenerationTest.linkedExistingCharacterActsAsSupportingNpcThenCanTakeSoloSpotlight` verifies real fake-provider requests in both roles and no double speaker. `NpcBankPromotionUiTest` covers the selection UI.

## Roadmap blockers still open

- Encrypted World archive import now explicitly remaps both Living Cast link kinds: a trusted promoted NPC and Character share one **new** ID in the imported World, and a separate chat-local NPC link points to the newly imported Character. Known plain-text manual profile fields stay intact. Unknown opaque reference-bearing extensions still fail closed rather than becoming dangling links. `WorldArchiveHistoryTest` covers both cases; full encrypted archive restore remains a separate acceptance gate.
- Expanded cross-World cast identity reconciliation and full regeneration/variant rollback QA still require explicit acceptance before a release.
- Persistent Campaigns, checkpoints, journal/timeline, separate Campaign memories, opt-in bounded AI memory extraction and World intelligence remain major unfinished stages.
- Persistent Campaigns, chapters/journal/timeline, and opt-in bounded paid AI memory extraction/review remain unfinished stages.
- Nothing is ready for a user-device update or production signing solely from this change.
