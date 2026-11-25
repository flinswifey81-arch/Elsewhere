# NPC Prose Discovery — real Room provenance acceptance (2026-11-24)

Status: draft PR #49 only; current commit requires exact-head CI before marking green.

`NpcProseVariantProvenanceTest` uses real in-memory Room to ensure NPCs created from explicit named quoted dialogue (without model metadata or extra OpenRouter calls) follow the selected response:

- A new spoken NPC can be discovered in the primary response.
- Regeneration to a different spoken NPC removes the original from the applied story projection, while preserving their historical storage.
- Switching back and forth selects only NPC knowledge from that response.
- Deleting a selected variant repairs the surviving variant and does not reuse the deleted NPC's current story presence.
- Wack excludes pre-boundary generated dossiers from all active generation context; historical provenance remains available.
- Continue introduces only newly speaking NPCs, never reapplies an old segment on retry, and deleting the continued message removes all source-linked NPC changes.
- The protected active Persona and responding Character are not mistakenly created as NPC dossiers, and unnamed guards do not become invented identities.

The acceptance is deterministic and offline. It cannot prove unstructured real-model reply quality or justify closing #50 and #51 without actual playtesting.
