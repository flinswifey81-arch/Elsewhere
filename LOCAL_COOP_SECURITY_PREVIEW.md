# Local Co-op bounded-test security status

## Current status

Local Co-op LAN networking is enabled in source for a narrowly bounded two-phone test using the user's signed release build. The feature remains inactive until a person opens a Group chat, chooses **Local Co-op**, reviews the disclosure, and explicitly confirms **Host** or **Join**. Opening Elsewhere, opening a chat, or opening the Local Co-op screen does not start a listener.

This is not a production-readiness or device-verification claim. No APK was installed, no real listener was opened, no phone security or firewall setting was changed, and no paid provider request was made during implementation. Use [docs/LAN_COOP_BOUNDED_TEST.md](docs/LAN_COOP_BOUNDED_TEST.md) for the required phone run.

Current persistence/protocol baseline:

- Room schema: **v8**, with nondestructive `MIGRATION_7_8` and durable message-delete tombstones.
- Co-op protocol: **v4**.
- Invite format: one versioned `ELSEWHERE-COOP-1:` value containing the private LAN address and port, room/member identifiers, rotating invite secret, and pinned host public identity.
- Network boundary: explicit RFC1918 IPv4 literals only; public, loopback, link-local, multicast, wildcard, malformed, and DNS-hostname destinations are rejected.

## Security and privacy boundary

- Each room uses a non-exportable P-256 host identity. Each member uses a room-scoped P-256 membership identity.
- Session setup combines a rotating high-entropy invite secret, authenticated P-256 ECDH, ECDSA/SHA-256 transcript signatures, HKDF-SHA256, and directional AES-256-GCM channels with exact monotonic counters.
- A valid encrypted connection is still only pending. The host sees the complete membership fingerprint and must explicitly Accept or Reject it.
- The host assigns durable monotonic event order. Duplicate IDs are idempotent only when their origin and ordered payload content match exactly; gaps, conflicting replays, wrong-room data, wrong senders, and unauthorized event types are rejected.
- Reconnect and resync are byte- and count-bounded. Presence is pessimistically reset on host restart and restored only after authenticated reconnect.
- Durable delete tombstones are backfilled from valid v7 delete events and prevent deleted message IDs from being recreated by replay or snapshot. Delete processing transactionally removes the message, attachment rows, NPC projections, generation metadata, and repairs variant-primary state; file cleanup is best-effort and retried by existing reconciliation.
- API keys, encrypted credential material, provider Authorization data, account-wide provider data, full Character/Persona cards, private notes, author notes, durable memories, Lorebook source/notes, NPC records, images, unrelated chats, drafts, and appearance settings are not room payloads.
- Host seeding rejects chats with image attachments or stored raw NPC control regions. Seed serialization also strips prior-room responsibility/provenance from the wire copy while leaving local history unchanged.
- Client Join requires an empty disposable Group chat. Images and NPC Bank automation remain visibly unavailable while co-op is configured.
- Wack and Update Memory Now remain local-only and are visibly blocked while co-op is configured. Saved memories, Lorebooks, NPC records, settings, and transcript data are not deleted.

## Provider payer boundary

Supported policies are:

- each participant pays for their own generations;
- the host covers generations;
- one selected participant covers generations.

Selecting a payer does not authorize spending. The assigned payer sees requester, Character, operation, model, routing mode, endpoint information when applicable, and fallback behavior, then must choose **Approve once** or **Decline**. The payer recomputes the provider-bound context fingerprint locally before execution. The provider call runs only on the payer device with that device's local encrypted credential; no API key crosses the room.

Generation request, approval, execution, result, and completion states are durable. An executing request is not silently retried after restart. Character result events are bound to the request, assigned payer, Character, operation, and deterministic event ID. Keep Partial retains the complete attempted message so a host-committed result can be retried byte-identically after an ambiguous acknowledgement. Terminal completion retries require the exact canonical payload bytes.

## Lifecycle and user controls

- Host binds the exact selected active private IPv4 address; it does not bind a wildcard interface.
- Join uses only a parsed and revalidated complete invite value.
- Address, connection state, full fingerprints, members, payer policy, and pending admission are visible.
- Host, Join, Resume, Stop listening, Leave room, and End co-op are explicit actions.
- Backgrounding the activity stops active transport. Returning does not reopen a listener; Resume is explicit and rotates the invite secret.
- Failed and cancelled Host, Resume, and Join startup paths run non-cancellable cleanup, close the attached transport, clear pending protocol state, and persist the local room/member offline.
- Pairing Copy/Share is shown only for a connected host and always uses the persisted bound address. A stopped or failed room cannot expose a stale invite card.

## Source-review gates

The final read-only reviews reported no remaining P1/P2 source blocker in the reviewed tree:

- Security review: authentication, admission, address restriction, migration/tombstones, replay/resync, payer consent, private-data boundaries, and transport cleanup.
- UI/state review: five palettes, Home layout, explicit co-op state/actions, invite validation/share state, full fingerprints, long-dialog scrolling, payer disclosure, draft serialization, and unsupported-tool messaging.
- Focused review: command-collector survival, terminal acknowledgement retention, byte-identical Keep Partial retry, canonical completion idempotence, primary-variant seed order, Continue stale-result rejection, transcript freeze, and cancellation-safe transport replacement.

Final quiescent automated evidence on 2026-10-09: **231/231** unit/Robolectric tests passed across **36** suites with zero failures, errors, or skips; fresh debug assembly executed **38/38** tasks successfully; fresh debug lint executed **30/30** tasks successfully and reported **0 errors / 53 warnings**; staged and unstaged `git diff --check` found no whitespace error. These results do not replace device checklist results.

## Known residual risks and limits

- Real two-phone Wi-Fi behavior, OS backgrounding, process restart, reconnect, paged replay, deletion propagation, and listener closure remain unverified until the bounded device run.
- RFC1918 validation cannot distinguish the intended trusted Wi-Fi interface from a VPN, tether, or corporate private route. Confirm the displayed address belongs to the intended test LAN.
- Attachment file deletion is best-effort after the transactional database delete. This does not enable attachment sync; co-op rejects image-bearing host chats.
- Context verification and provider startup currently perform two preparation passes. The co-op UI freezes the relevant transcript operations, but future hardening should pass the verified prepared request directly into execution or rehash immediately before dispatch.
- The deterministic context fingerprint does not reveal the underlying context directly, but a future protocol could add a request-specific keyed/salted attestation for additional resistance to guess testing.
- Home/profile visual polish remains an incremental product task; the current work does not claim final artwork or complete archive/parchment fidelity.

## Test authorization boundary

The source is prepared only for the bounded test described in [docs/LAN_COOP_BOUNDED_TEST.md](docs/LAN_COOP_BOUNDED_TEST.md). Before running it:

1. Use the same user-signed protocol-v4 build on both named phones.
2. Use a trusted LAN and verify the displayed private address; do not change firewall, router, or Android security settings.
3. Share the invite out of band and compare the full host/member fingerprints before accepting.
4. Prefer connectivity-only validation first. Decline/timeout checks require no provider call.
5. Make a paid provider request only under a separate explicit approval naming the payer, Character, model, and spending expectation.
6. Stop/leave the room and discard invite material after the run.

No commit or push is included in this work.
