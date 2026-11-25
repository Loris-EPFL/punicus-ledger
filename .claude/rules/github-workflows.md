---
description: "The invariants of the GitHub workflows and scripts: unscoped PR triggers, the image builds in the merge queue, the queue's required checks, the Claude reviewer and what it reads, how fork PRs are reviewed, the release guards, the minSdk check."
paths:
  - ".github/**/*"
  - "scripts/**/*"
---

# Workflows and scripts

Loaded when you work in `.github/` or `scripts/`. Each workflow file carries
its own rationale in its header; read the header before changing the file.
Several files are inherited verbatim from biafra23/myotis and say so. Keep the
two repositories in step when one changes.

## Triggers

- **Every PR workflow's `pull_request` trigger is unscoped** (any base
  branch). A part PR of a multi-PR plan targets its feature branch and still
  needs Build and Test (`ci.yml`) and the Claude review. Do not narrow them
  to `main`.
- **The image builds run in the merge queue, not on a PR** (owner's request,
  2026-11-25): the Android APKs, the macOS dmg, the Windows msi and the iOS
  app (ios.yml's `app` job). A job-level `if` skips them on every PR, so
  their checks show as skipped, not absent. They run on `merge_group`, on
  `main`, on `v*` tags and on dispatch. This takes in the base-branch skip
  of the owner ruling of 2026-10-09: a part PR builds no image, and the
  final PR builds them all in the queue. What a part PR does about it:
  `.claude/skills/feature-branch/SKILL.md`, *What the workflows do with part
  PRs*.
- **The image builds run only where they can tell something** (owner's
  request, 2026-11-18; in the queue since 2026-11-25). `pr-scope.yml` lists
  the queue entry's files: the diff from `main` to the commit the queue
  tests. A change of docs alone skips them all. The dmg builds only for a
  change to the desktop packaging or the build. The msi builds only for the
  paths its PR trigger was filtered on before. The iOS device link runs only
  for a change to the iOS host or the build. `main` builds everything on
  every push, and so does the final PR of a multi-PR plan (a `feature/`
  branch into `main`) in the queue.
- **On a PR**, Build and Test and the Screenshots workflow run whatever it
  changes. ios.yml's targets job (the iOS targets and the desktop UI tests
  on macOS) runs on every PR that changes code, scoped by `pr-scope.yml`
  from the PR's files, and is skipped in the queue. Build and Test runs
  again in the queue, on the queue's commit. On a tag the desktop UI tests
  on macOS still run in the dmg leg, before the upload.
- **The label `full-ci` turns this scoping off**: the queue then builds the
  dmg, the msi, the APKs and the iOS app (with its device link) whatever the
  PR changes, and the PR runs the targets job even for docs. Add it before
  the PR is queued; on a PR, push or use *Re-run all jobs* (*Re-run failed
  jobs* leaves a skipped job skipped). It does not reach the deb (no PR
  trigger).
- `pr-scope.yml` is fail-open: an API error or a file list it cannot trust
  builds everything. A caller tests its outputs with `!= 'false'`. Its
  `dmg`, `ios_device` and `msi` lists skip a path not on them, so a
  directory a Gradle task reads from must be added there.
  `scripts/test_pr_scope.py` tests the rules, and ci.yml runs it.
- A superseded PR run of Build and Test is cancelled; a run on `main`, a tag
  or a queue entry never is (owner's request, 2026-11-18).
- Build and Test and the other push triggers fire only on `main` and on `v*`
  tags. A clean `main` merge into a feature branch is first fully tested by
  the next PR into or out of the feature branch.
- A job that needs `pr-scope.yml` may carry an `if:` only if it is true on
  every event but `pull_request` and `merge_group`: a skipped job skips the
  release uploads behind it on a tag. Its outputs are `true` on every other
  event, and no release upload runs on those two.

## The merge queue

(Owner's request, 2026-11-24 and 2026-11-25.)

- A ruleset on `main` takes every PR through GitHub's merge queue, which
  merges with a merge commit, as before. The queue tests the PR merged onto
  `main` and onto the PRs queued ahead of it, and that tested commit is what
  lands on `main`.
- **Adding a PR to the queue merges it.** It is the owner's action, like
  every merge into `main` (owner ruling, 2026-08-31).
- The required checks are Build and Test, `Build APKs (debug + unsigned
  release)`, `macOS .dmg result`, `Build Windows .msi (x64)`, `iOS app
  (build + start on a simulator)` and `iOS targets + macOS UI tests`, all
  from GitHub Actions. A PR must pass them to be queued, and the queue's
  commit must pass them to merge.
- A required check must report on both `pull_request` and `merge_group`. A
  job that runs on only one of them is skipped on the other by a job-level
  `if`, never left out: GitHub takes a skipped check as passed, and waits
  for an absent one until the queue's timeout. So a required workflow takes
  no path filter.
- A skipped matrix job reports under its unexpanded name (`Build macOS .dmg
  (${{ matrix.arch }})`), so a matrix is required through a result job that
  needs it, as `desktop-dmg.yml` does.
- A failure in the queue shows on the queue's run, not among the PR's
  checks, and takes the PR out of the queue. The PR's timeline says so.

## The Claude reviewer

`claude-review.yml` and `.github/claude-review-prompt.md`.

- It reviews once per PR (opened, reopened, ready for review), not on every
  push. A re-run replays the original commit. Close and reopen the PR for a
  fresh review.
- It reads its prompt file from the PR's base commit, never from the PR's
  checkout. claude-code-action restores the root `CLAUDE.md`,
  `CLAUDE.local.md` and `.claude/` from the base branch's tip (as its
  docs/security.md documents). That is why the agent rules and skills live
  under the root `.claude/`: a PR cannot rewrite what its own reviewer reads.
  A `CLAUDE.md` or `.claude/rules/` in a subdirectory is NOT restored.
- A PR that edits `claude-review.yml` cannot review itself: the action skips,
  and the job goes green with a warning. The same happens on any branch whose
  copy of that file differs from `main`'s, such as a feature branch that has
  not merged a change to it. A green `claude-review` job alone is not a
  review.
- Nothing waits for the review. It held the artifact builds on a PR until
  those moved to the merge queue (2026-11-25), where the review has long
  finished; the gate that held them, `wait-for-claude-review.yml`, went with
  them.
- The reviewer's prompt opens "Read CLAUDE.md first". The author-side rules
  (`.claude/skills/pr`) are not addressed to it, and it could not follow them
  anyway: `gh api` is not on its allowlist.

## Fork PRs are reviewed manually, never by the CI reviewer

(Owner's decision, 2026-08-15.)

- The workflow skips forks (`head.repo.full_name == github.repository` in its
  `if:`). A `pull_request` run from a fork gets no secrets. The deeper reason
  is trust: a fork PR's tree is attacker-controlled, and an automated
  reviewer that reads it can be prompt-injected.
- A PR can no longer rewrite the reviewer's prompt file or the root
  instruction files, which are read or restored from the base. That does not
  make a fork tree safe to hand the reviewer: whoever opens a PR picks its
  base, instruction files below the repo root are not restored, and the diff
  itself is always attacker text.
- So do NOT wire fork PRs into the automated reviewer. Instead:
  1. The owner reads the diff first, watching for injection in `CLAUDE.md`,
     `.claude/**`, `.github/**`, build scripts, postinstall hooks, comments
     and encoded blobs.
  2. If wanted, a review is run manually from a trusted session restricted
     to read, diff and comment tools. It never builds or runs the fork tree:
     merely `./gradlew ...` executes fork-controlled build scripts locally.
     CI already builds fork PRs through the unscoped `pull_request` in
     `ci.yml`, but secretless and sandboxed; a local session is neither.
- Held to that, nothing auto-merges, and an injected reviewer's worst case is
  a comment a human still acts on.
- If automating fork review is ever reconsidered: pin the prompt and the
  instruction files to the base ref, never execute fork code, keep reviews
  `COMMENT`-only, and lock down runner egress. Myotis PR #375 has the
  discussion that produced this note.

## Releases

- `release-version-guard.yml` is called by every workflow that uploads to a
  release. It accepts `vX.Y.Z` and `vX.Y.Z-<suffix>` (rc and hotfix shapes),
  nothing else, and refuses the tag before anything is uploaded unless
  `./gradlew -q :printReleaseVersion` prints the tag's version core.
- `sign-release` (android-apk.yml) signs the release APK on a tag with the
  key in the `android-release` environment, and only for a tree with
  `android-app/release-signing-cert.sha256`. Without that pin a tag publishes
  the desktop installers and the debug APK, and no release-signed Android app
  (owner's request, 2026-10-21). With the pin, a signing failure publishes no
  Android asset at all.
- Every `v*` tag publishes a pre-release. Each workflow creates the release
  race-tolerantly and uploads its assets. The Windows msi is left out of tags
  until Myotis ships a Windows engine library (owner's decision, 2026-10-21):
  it builds on `main`, and a dispatch on a tag publishes it.
- The procedure for cutting a release: `.claude/skills/release/SKILL.md`.

## The Screenshots workflow

(Owner rulings, 2026-11-04; `.claude/skills/screenshots/SKILL.md`.)

- `screenshots.yml` is the one workflow that writes to the repository. It
  commits what it renders under `docs/screenshots/` (the PNGs and the
  folder's `README.md` and `index.html`), and nothing else, to a same-repo
  PR's branch, and never to `main`.
- It pushes with the `SCREENSHOTS_DEPLOY_KEY` deploy key, because a push
  with `GITHUB_TOKEN` starts no workflow and the new head would go untested.
  The key, and any change to it, is the owner's.
- The key is held by a job of its own, which runs none of the PR's code.
  The render job, which runs the PR's build, holds no write credential and
  hands its commit over as a git bundle. The push job pushes it only if it
  is one commit on top of the PR's head that changes nothing outside
  `docs/screenshots/`. The key never reaches the render job's runner: a
  build script there could read it, or leave a process behind that reads it
  later.
- A fork PR gets no secrets: it renders, compares and fails when the
  screens differ, and its description is left alone.
- On `main` it only verifies that the committed set equals the render.
- PR-controlled values reach its scripts through `env:`, and every action
  is pinned to a commit, as `scripts/check_security_invariants.py` checks.

## Scripts

- `scripts/check_apk_min_api.py` enforces the minSdk 29 JDK-API budget
  against the built APKs' dex. When it runs, and what may never be
  allowlisted: `.claude/rules/platform.md`, *The minSdk 29 JDK-API budget*.
- `scripts/screenshots.py` writes the screenshots table into a PR's
  description and holds `docs/screenshots/` to its size budget.
  `scripts/test_screenshots.py` tests it, and the workflow runs those tests
  first.
- The APK workflow also checks the built manifests for the preview tooling's
  `PreviewActivity`, which must stay out of every APK (`.claude/rules/ui.md`,
  *Every screen has previews*).
