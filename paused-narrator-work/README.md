# Paused dedicated narrator/NPC work

This branch preserves the exact uncommitted work that was paused before Scenario Mode began. It is based on `af4b8e019e36a804e288241bbaa25625eb7755a8`, the same commit as `personal/local-backup-restore` at the time of archival.

The archive is intentionally separate from `feature/scenario-mode` and has not been applied to this branch.

Files:

- `changes.patch`: binary-safe Git patch for 11 modified tracked files.
- `new-files.tar`: archive containing the four untracked source/test files.

SHA-256 checksums:

```text
75236c5aa49f504b2236748994c46e8f759ed3fdb43d7155b96c8bc960673a27  changes.patch
81caf6808c73a1976643dac90d620a557823a5e32cec2f1ea5551c067c47a83c  new-files.tar
```

To inspect without changing a worktree:

```bash
git apply --stat paused-narrator-work/changes.patch
tar -tf paused-narrator-work/new-files.tar
```

To restore into a clean worktree based on the recorded base commit:

```bash
git apply paused-narrator-work/changes.patch
tar -xf paused-narrator-work/new-files.tar
```

The patch and tar archive should be treated as one snapshot.
