# Elsewhere CI execution policy — fast draft feedback, full acceptance before release

This corrects the repeated multi-suite launch problem on PR #49. Previously, every small draft change triggered up to eight independent Android workflows, some with 60–90-minute timeouts. `cancel-in-progress` did not prevent the next expensive run from starting. The assistant cannot monitor GitHub Actions when a chat is idle unless an actual scheduled automation is separately configured.

## Draft development

- One **Elsewhere Draft Smoke** workflow checks the PR head on app, build, QA, and workflow-file changes. It compiles the Sandbox Kotlin app and runs four targeted, offline story/NPC/memory regression suites. Its job has a 20-minute timeout, not a guaranteed completion time. No OpenRouter calls, owner secrets or installation.
- Its per-PR concurrency group cancels obsolete smoke runs.
- Eight existing deep-check workflows retain their current names, tests and `workflow_dispatch` capability. During a **draft** PR, their jobs are skipped on PR events; they do not install SDKs, compile, or run. The old Stage 1 eligibility check remains conditionally skipped as before.
- Documentation-only edits do not activate the smoke workflow.
- Batch changes; do not continuously commit while a release-grade suite is running unless its failure warrants intervention.

## Full verification (required before any release proposal)

- The existing full checks run **on the explicit `ready_for_review` PR event**, on subsequent non-draft PR pushes, or individually by `workflow_dispatch` when explicitly requested. This preserves automatic full coverage after review readiness and manual exact-head checks during a draft.
- The normal full acceptance includes APK identity/signing safety, native-v15 Room migration/rollback, actual emulator package upgrade, Memory V2 (including pinned-v15), Worlds/RP, World archives, Continue and pre-freeze. These checks are not replaced by smoke tests.
- Treat a skipped full check on a draft as **NOT verified**; do not use skip status to authorize an APK, merge or production install. Verify the full set passed for the exact final commit SHA before any release. If a branch-protection rule requires historical status names, preserve those names and adjust review gates before merging; the GitHub integration did not have permission to read branch protection rules.
- A manual Workflow Dispatch starts tests, not a production signed APK or phone installation. The separate release candidate workflow still requires the owner's explicit candidate approval.
- Reports should distinguish `implemented`, `smoke-tested`, `full-suite verified`, and `release approved`. Never claim background monitoring, notification or asynchronous follow-through without an actual automation.

**No paid API calls, production signing keys, production APK installation or user-device data are involved.**
