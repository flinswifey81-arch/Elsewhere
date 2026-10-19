# Elsewhere Lorebook v1 Guide

Lorebooks are reusable collections of world facts. They work in both Solo and Group chats, but they never become global automatically. A Lorebook affects a chat only after you explicitly attach it from that chat's **Chat Lorebooks** screen.

## Quick start

1. Open the **Lorebooks** tab.
2. Choose **New Lorebook** to author one manually, or **Import JSON** to choose a file or paste JSON.
3. Review an import preview before anything is saved.
4. Open the Solo or Group chat where the lore belongs.
5. Tap the book icon in the chat top bar.
6. Attach the Lorebook and leave **Use in this chat** enabled.
7. For `manual` entries, turn on **Manual active** when the scene needs them.

An attached Lorebook works through the same generation path for normal replies, Retry, Regenerate, Continue, variants, and every provider-routing mode. Elsewhere still makes one independent model request per selected Group Character.

## Manual editor reference

**New Lorebook** opens with these defaults: **Lorebook name** = `New Lorebook`, **Scope type** = fixed `story`, **Story ID (optional metadata)** = blank/unbound, **Description** = blank, **Lorebook enabled** = on, and no entries. Entering a Story ID records metadata only; attachment to every Solo or Group chat remains explicit.

**Add Entry** opens a new entry with these fields and defaults:

- **Title** = `New Entry`.
- **Entry enabled** = on.
- **Activation** = **Keyword**. The alternatives are **Always** and **Manual**.
- For Keyword activation: **Keywords (one per line)** and **Additional keywords (one per line)** start empty; **Match any** is selected; **Case sensitive** is off; **Whole words** is on.
- **Canon content** = empty and **Canon locked** = on. Canon content is directly editable while creating the entry. For an existing entry, Canon is read-only until **Canon locked** is turned off and **Update Canon Explicitly** is used.
- **Current content sent when active** = empty. Empty remains intentionally empty and never falls back to Canon.
- **Priority** = `50` (**Normal**). Presets are **Low** `25`, **Normal** `50`, **High** `75`, and **Critical** `100`; the text field accepts any signed 32-bit integer.
- **Position** = fixed `lore`.
- **Tags (one per line; private metadata)** = empty.
- **Private notes (never sent to the model)** = empty.

The editor generates and preserves machine fields for the Lorebook and entries: stable IDs, `schema_version`, JSON `type`, creation/update timestamps, and the fixed injection position. They are intentionally not manual form fields. **Save Entry** requires a nonblank title and a valid integer priority. **Save** requires a nonblank Lorebook name; repository validation applies all limits listed below.

## Per-chat controls

The book icon in either a Solo or Group chat opens **Chat Lorebooks**. Each library book has an **Attached / Not attached** switch. An attached book exposes:

- **Use in this chat**, which temporarily disables or enables that attachment without removing its saved overlays;
- **Manual active**, shown only for `manual` entries and stored separately for that chat;
- **Edit Chat Current**, whose **Current content for this chat** starts from the existing chat override or the reusable Current and is saved with **Save Chat Override**;
- **Use Book Current**, shown when an override exists and removes only that chat override.

Detaching preserves Chat Current overrides and Manual active choices for later reattachment. A disabled library book, disabled attachment, or disabled entry injects nothing.

## Canon, Current, and chat Current

- **Canon** is the reusable baseline. Normal content editing changes Current, not Canon.
- **Current** is the reusable working version sent when the entry activates.
- **Chat Current** is an optional per-chat override. It never changes the reusable book or another chat.
- **Reset Current to Canon** copies Canon into Current after confirmation. It does not change activation fields, IDs, tags, notes, or timestamps.
- To change Canon, first unlock it and then choose **Update Canon Explicitly**. Unlocking alone does not change Canon.
- An empty Current is intentional. Elsewhere never falls back to Canon just because Current is empty.

Detaching a Lorebook preserves that chat's Current overrides and manual activation choices for later reattachment. Deleting a book, deleting an entry, or replacing a book with JSON that omits an old entry explicitly removes the affected local state.

## Activation modes

### Keyword

The entry activates when its keyword rule matches the conversational text selected for the request: up to the latest 24 primary conversation messages plus the pending message. Lorebook text and private notes are not scanned, so entries do not recursively trigger one another.

`keys` and `secondary_keys` are combined into one set:

- `match: "any"` activates when any combined key matches.
- `match: "all"` requires every combined key to match.
- `case_sensitive` controls letter case.
- `whole_words` prevents a key such as `gate` from matching `gateway`. Unicode letters, numbers, and underscores count as word characters.
- A keyword entry with no keys stays inactive.

### Always

The entry activates for every request while its entry, Lorebook, and chat attachment are enabled.

### Manual

The entry activates only when **Manual active** is switched on in that chat. Manual activation is per chat and is not exported as part of the reusable Lorebook.

## Priority

Higher numbers are injected first. The editor provides Low `25`, Normal `50`, High `75`, and Critical `100`, but any signed 32-bit integer is valid and preserved exactly through editing and JSON round trips.

Priority controls ordering only. Lorebook v1 does not implement probability, recursion, cooldowns, regular expressions, or separate token budgets. Every active entry is added to the same system/instruction context as the Character, Persona, memory, and history; it therefore consumes the selected model's shared context window. Priority does not reserve space or silently discard lower-priority lore.

## What reaches the model

For each active entry, the request contains only:

- the entry title;
- the selected Current content (chat override when one exists, otherwise reusable Current).

The request does not contain Lorebook IDs, entry IDs, activation keys, tags, notes, timestamps, schema metadata, Canon, or manual activation state. Lore is system/instruction context, never fake dialogue.

Lorebook content is world truth, not proof that every Character knows it. The compiler explicitly tells the model to respect the selected Character's established perspective. For secrets in Group roleplay, also keep each Character's knowledge and relationships asymmetric in their own Character card.

## Import, duplicates, and export

Import supports Android JSON file selection and pasted JSON. Validation occurs before the preview.

If the Lorebook ID already exists:

- **New Copy** creates a fresh book ID and fresh entry IDs; the existing book is unchanged.
- **Replace** performs one database transaction. Existing chat attachments and overlays for matching entry IDs survive. Entries omitted from the replacement and their overlays are removed.
- **Cancel** writes nothing.

Entry IDs are globally stable. If an imported entry ID already belongs to a different Lorebook, the preview names the conflict and offers **New Copy**, which generates safe new book and entry IDs. Direct import or replacement never silently steals or overwrites another book's entry.

Export includes the reusable Lorebook, Canon, Current, activation settings, arbitrary priority, tags, private notes, and timestamps. It never includes chat attachments, chat Current overlays, manual chat activation, chats, messages, or API keys.

`scope.type` must be `story`. `story_id: null` means unbound, not global; the book still must be attached to each chat. For compatibility with early files, a missing `scope` is accepted as `{ "type": "story", "story_id": null }`. Other scope types and injection positions are rejected instead of being silently reinterpreted.

## Accepted JSON schema

Root fields:

- `schema_version`: required integer; currently `1`.
- `type`: required; exactly `"elsewhere_lorebook"`.
- `id`: required stable ID using 1–200 ASCII letters, digits, `.`, `:`, `_`, or `-`.
- `name`: required nonblank string, at most 200 characters.
- `description`: optional string, at most 10,000 characters.
- `enabled`: optional boolean; defaults to `true`.
- `scope`: optional object with `type: "story"` and nullable/nonblank `story_id` of at most 200 characters.
- `entries`: required array, at most 500 entries.

Entry fields:

- `id`: required stable ID, unique within the file.
- `title`: required nonblank string, at most 300 characters.
- `enabled`: optional boolean; defaults to `true`.
- `activation`: required object. `mode` is `keyword`, `always`, or `manual`; `match` is `any` or `all`; each of `keys` and `secondary_keys` contains at most 100 nonblank strings of at most 200 characters each.
- `canon`: required object with string `content` and optional boolean `locked`.
- `current`: required object with string `content`, including an intentionally empty string.
- `injection`: required object. `position` must be `lore`; `priority` may be any signed 32-bit integer.
- `tags`: optional array containing at most 100 nonblank strings of at most 200 characters each.
- `notes`: optional private string, at most 100,000 characters.
- `created_at` and `updated_at`: optional ISO-8601 UTC strings such as `"2026-10-04T00:00:00Z"`. Legacy non-negative epoch-millisecond integers remain accepted on import. Export always writes canonical ISO-8601 strings.

Each Canon or Current content string may contain at most 200,000 characters. The complete UTF-8 JSON file may contain at most 2,000,000 bytes. Unsupported or misspelled fields are rejected so data is never silently discarded.

## Valid two-entry example

This exact example is exercised by the production importer in the automated test suite.

```json
{
  "schema_version": 1,
  "type": "elsewhere_lorebook",
  "id": "evermere-primer",
  "name": "Evermere Primer",
  "description": "Places and customs used in the Evermere story.",
  "enabled": true,
  "scope": {
    "type": "story",
    "story_id": null
  },
  "entries": [
    {
      "id": "evermere-moonwell",
      "title": "The Moonwell",
      "enabled": true,
      "activation": {
        "mode": "keyword",
        "keys": ["Moonwell", "moon well"],
        "secondary_keys": ["silver water", "well beneath the abbey"],
        "match": "any",
        "case_sensitive": false,
        "whole_words": true
      },
      "canon": {
        "content": "The Moonwell lies beneath the ruined abbey. Its water reflects possible futures but never guarantees them.",
        "locked": true
      },
      "current": {
        "content": "The Moonwell lies beneath the ruined abbey. Since the autumn eclipse, its water has shown only the northern road."
      },
      "injection": {
        "priority": 75,
        "position": "lore"
      },
      "tags": ["location", "magic"],
      "notes": "Private author note: reveal the cause of the eclipse later.",
      "created_at": "2026-10-04T00:00:00Z",
      "updated_at": "2026-10-04T00:00:00Z"
    },
    {
      "id": "evermere-guest-right",
      "title": "Evermere Guest Right",
      "enabled": true,
      "activation": {
        "mode": "always",
        "keys": [],
        "secondary_keys": [],
        "match": "any",
        "case_sensitive": false,
        "whole_words": true
      },
      "canon": {
        "content": "A host who offers bread and salt owes a guest safety until the next sunrise.",
        "locked": true
      },
      "current": {
        "content": "A host who offers bread and salt owes a guest safety until the next sunrise. Breaking guest right causes lasting public disgrace."
      },
      "injection": {
        "priority": 50,
        "position": "lore"
      },
      "tags": ["custom", "law"],
      "notes": "Private planning note; never model context.",
      "created_at": "2026-10-04T00:00:00Z",
      "updated_at": "2026-10-04T00:00:00Z"
    }
  ]
}
```

## Prompt for another LLM

Paste this prompt into the LLM, replace the bracketed source material, and save its JSON-only response as a `.json` file.

```text
Convert the source material below into one Elsewhere Lorebook v1 JSON object.

Return JSON only: no Markdown fence, introduction, commentary, or trailing text.

Use exactly this structure:
- Root: schema_version=1, type="elsewhere_lorebook", stable id, name, description, enabled, scope, entries.
- Scope: {"type":"story","story_id":null}. Null means unbound, not global.
- Every entry: id, title, enabled, activation, canon, current, injection, tags, notes, created_at, updated_at.
- activation must contain mode, keys, secondary_keys, match, case_sensitive, and whole_words. mode must be "keyword", "always", or "manual"; keys and secondary_keys must be JSON arrays of strings; match must be "any" or "all"; case_sensitive and whole_words must be booleans.
- canon must contain content and locked. content must be a string and locked must be a boolean. current must contain string content, including an intentionally empty string when appropriate.
- injection must contain integer priority and position="lore".
- Use priority 25 for background flavor, 50 for normal facts, 75 for important continuity, and 100 only for critical rules. Other integer priorities are allowed when justified.
- Use stable IDs containing only ASCII letters, digits, dots, colons, underscores, and hyphens.
- Put durable source truth in canon.content.
- Initially copy Canon into current.content unless the source explicitly describes a changed present state. Never substitute Canon for an intentionally empty Current.
- Put private planning/reminder material only in notes.
- Do not add fields outside the schema. Do not use regex, probability, recursion, cooldowns, or unsupported injection positions.
- Split lore into focused entries. Prefer precise activation phrases over very common single words.
- Use keyword mode for facts relevant only when a topic appears, always mode only for rules needed in nearly every response, and manual mode for scene facts the user should toggle.
- created_at and updated_at must be ISO-8601 UTC strings, for example "2026-10-04T00:00:00Z".
- Limits: at most 500 entries; IDs at most 200 characters; names at most 200; titles at most 300; description at most 10,000; content at most 200,000; notes at most 100,000; each `keys`, `secondary_keys`, and `tags` array separately contains at most 100 nonblank strings of at most 200 characters each; entire UTF-8 JSON at most 2,000,000 bytes.
- Do not invent facts absent from the source. If a required field cannot be grounded, use an empty description/notes/content where allowed or a neutral organizational title, but do not fabricate lore.

Source material:
[PASTE YOUR LORE, WIKI NOTES, SETTING DOCUMENT, OR CANON SUMMARY HERE]
```

After generation, import the result and read the preview. If validation fails, give the exact Elsewhere error back to the LLM and ask it to correct only that schema problem without inventing new lore.
