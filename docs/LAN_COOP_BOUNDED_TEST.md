# Elsewhere local co-op: bounded two-device test

This checklist is for a deliberate test between two phones on one trusted local network. It is not a claim that local co-op has been manually verified. Do not use public Wi-Fi, port forwarding, a VPN relay, or an Internet-exposed host.

## Safety boundary

- Hosting and joining are inactive until a person opens a Group chat, chooses **Local Co-op**, reviews the disclosure, and confirms **Host** or **Join**.
- Elsewhere does not start a LAN listener at app launch. Moving the app to the background stops the active transport.
- The invite is a sensitive, versioned value. It contains the address, port, room identifiers, rotating invite secret, and pinned host public identity. Share it only with the intended participant through a trusted channel; do not paste it into issue reports or logs.
- A valid encrypted connection is still staged. The host must compare the full membership fingerprint and explicitly accept the join.
- API keys, encrypted credential material, full Character or Persona cards, private notes, durable memories, Lorebook source and notes, NPC records, images, unrelated chats, and account-wide provider information are not room payloads.
- Room members do receive room message text, speaker snapshots, participant and Persona labels, the AI Character roster, payer policy, generation requests, and accepted generated results.
- Images and NPC Bank automation are visibly unavailable while the room is active. Do not use a chat containing message images or raw NPC control regions as a co-op host source.
- Provider work remains explicit. Selecting a payer does not spend money. The payer device sees the requester, Character, operation, model, routing mode, selected endpoint when applicable, and whether fallback is allowed before choosing **Approve once**.

## Before the run

1. Use the same user-signed build and protocol version on both phones. Install it without clearing app data if the purpose includes migration/data-preservation testing.
2. Back up important local data independently. Never include an API key or invite in a captured diagnostic bundle.
3. Confirm both phones are on the same trusted LAN and can reach each other locally. Do not change firewall, router, or Android security settings for this test.
4. On the host phone, choose a Group chat with no image attachments and no unresolved partial model reply. On the joining phone, use an empty disposable Group chat.
5. Confirm neither phone is currently generating, saving a message, awaiting a partial-reply Keep/Discard decision, or performing another Host/Join/Reconnect action.
6. Decide whether the test is connectivity-only. A connectivity-only test requires no provider call and must stop before approving generation.

## Pairing and admission

1. On the host, open the Group chat menu, choose **Local Co-op**, enter the local display name and Persona, then choose **Host LAN Room**.
2. Read the disclosure and choose **Confirm Host**. Confirm that the screen shows **Listening**, the local address and port, a pinned host fingerprint, and **Stop listening** / **End co-op** controls.
3. Confirm that the read-only bound address is the intended phone Wi-Fi address, then use **Copy invite** or **Share invite**. Treat the entire value as a secret.
4. On the second phone, open its empty disposable Group chat, choose **Local Co-op**, select **Join**, paste the complete invite, and validate it. Confirm the preview shows the expected address and full pinned host fingerprint before continuing.
5. Choose **Pair & Join**, read the disclosure, and choose **Confirm Join**.
6. On the host, compare the full membership fingerprint with the joining phone through the trusted out-of-band channel. Accept only the expected request. Reject an unexpected or mismatched request.
7. Confirm both phones show the same room membership, Character roster, payer policy, and connected state.

## Data and ordering checks

1. Send one Persona message from each phone. Confirm each device can post only as its locally owned Persona and both transcripts converge with correct speaker snapshots.
2. Edit and delete a permitted message. Confirm the remote delete removes the message, all of that message's attachment rows/files, projected NPC state, and related generation metadata without deleting unrelated data.
3. Disconnect and reconnect the member. Confirm catch-up does not duplicate messages. Attempting to replay a deleted message ID must not resurrect it.
4. Exercise a Character response with variants only if the room has no unresolved operation. Confirm exactly one primary variant remains and switching is frozen while generation or partial-reply resolution is active.
5. Exercise **Continue** only on a settled Character message. If that message is edited before the result returns, confirm the stale continuation is rejected instead of overwriting the edit.
6. Confirm Wack, Update Memory Now, image attachment, and NPC Bank mutation controls explain that they are unavailable while co-op is active. Local saved memories, Lorebooks, NPC records, and settings must remain present.

## Payer modes and optional provider check

Test the policy display for:

- **Each participant pays for their own generations**
- **Host covers all generations**
- **Selected participant covers all generations**

For each mode, request a generation and verify the assigned payer device receives the approval card. Check the exact model and routing text:

- **AUTO**: automatic routing with provider fallback allowed; the resolved provider is not yet known.
- **PREFER**: the selected endpoint is shown and fallback is explicitly allowed.
- **LOCK**: the selected endpoint is shown and fallback is explicitly disabled.

Decline at least one request and allow at least one approval to expire. Both must result in zero provider calls. Restart or reconnect with an executing request and confirm it is not silently retried.

Only if the user separately approves a bounded paid test, approve exactly one request on the named payer phone. Verify one provider call, one Character result, one durable completion, accurate local usage metadata, and no duplicate charge or response after replay/reconnect. Do not make a paid call merely to complete this checklist.

## Lifecycle and exit

1. Background the host app. Confirm the listener stops and the member reports host-offline/suspended state.
2. Return to the host. Confirm listening does not restart by itself. Use the explicit reconnect/resume control; the invite secret should rotate.
3. Use **Stop listening** to stop transport while retaining the room configuration and chat.
4. Use **Leave room** or **End co-op** to remove only local co-op configuration. Confirm chat history, variants, local memories, Lorebooks, NPC records, settings, and unrelated chats remain.
5. Redact and discard invite material after the run. Do not change device or network security settings as cleanup.

## Record the result

Record device/Android versions, build identity, protocol version, Room migration path, LAN type, each checklist result, and any exact visible error. Keep API keys, invite strings, message text, private notes, memories, and provider account data out of the report.

Until this checklist is completed on two real phones, report the feature as **implemented and automated-tested, but not manually verified on devices**.
