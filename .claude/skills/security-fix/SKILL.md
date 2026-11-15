---
name: security-fix
description: "Fixing a vulnerability that is not public yet: a report from SECURITY.md (a private advisory), or one the owner hands over in private. Replaces Workflow steps 3 to 5: no public PR, no push to any branch, the CI checks run locally, a patch handed to the owner for the advisory's private fork. The owner merges, releases and publishes the advisory. Use before any work on such a report."
---

# Fixing a privately reported vulnerability

The owner's request, 2026-11-15, on Myotis #870. Myotis carries the same
procedure in `.claude/agent/security-fixes.md`; a change is made in both.

`SECURITY.md` promises a reporter two things. The discussion stays in the
private advisory. The advisory is published once a fixed release is out. The
Workflow points the other way: every work item gets a public PR, and PRs are
the project's public record. This skill replaces steps 3 to 5 of the
Workflow for an embargoed fix, and nothing else. Every other rule in
CLAUDE.md still applies: trust, keys and never merging first.

## When it applies

- A vulnerability that is not public yet, in this repository's code or in how
  it uses a dependency or the Myotis engine.
- The owner decides whether a report is embargoed. A report that is not a
  vulnerability, or one that is already public (a published CVE, an open
  issue), takes the normal Workflow. Ask when unsure. Never assume "not
  embargoed".
- A flaw in the engine itself is Myotis's. Tell the owner; it is fixed there,
  under Myotis's copy of this procedure.

## Two periods: before the merge, and until the advisory is published

**Until the owner has merged the fix, nothing leaves the machine** but the
patch and report sent to the owner (step 4).

- **No push to any branch of this repository.** The session's designated
  branch is public too: a pushed commit is a disclosure, whatever its message
  says. This overrides the harness's "commit and push when done".
- No PR, issue, discussion, comment or review reply about it, on GitHub or
  anywhere else.
- Nothing goes to an external service: no gist, no paste site, no issue
  tracker of a dependency. Reporting a dependency's bug upstream is the
  owner's call, through that project's private channel.

**From the merge until the advisory is published, only the wording is held
back.** The code is public from the merge on, so pushes and PRs are normal
again (steps 6 and 7 need them), but every one of them stays neutral:

- A branch name, commit message, code comment, PR, changelog entry or
  release note says what changed ("check X before Y"). It never says that it
  was a vulnerability, how it is exploited, what it exposes or who reported
  it.
- The release PR and its `CHANGELOG.md` entry follow the `release` skill
  as usual, in that neutral wording.

## Steps

1. **Code** the fix on a local branch. Do not push it.
2. **Run the CI checks locally.** GitHub runs no CI and no status checks in
   an advisory's temporary private fork, and no integration reaches it, the
   CI reviewer included. So run what `ci.yml` runs on what the fix touches:
   `./gradlew build`, and
   `python3 scripts/check_security_invariants.py` with its tests
   (`python3 -m unittest discover -s scripts -p 'test_check_security_invariants.py'`).
   Add a test that fails without the fix and passes with it. If the fix
   changes a screen, render its previews locally (the `screenshots` skill)
   and attach them to the report, not to GitHub. List every check as passed,
   failed, or not run with the reason; the iOS targets build on macOS only.
3. **Review** the diff with `/code-review`, locally (never `--comment`), and
   fix what it finds. Check it against `.claude/rules/trust.md` as a
   reviewer would.
4. **Hand it to the owner** as a patch: `git format-patch` into the primary
   working directory, with a short report: what the fix does, the checks of
   step 2 and their results, what could not be checked. In a cloud session
   the container is not the owner's machine: send both with the host's file
   tool (`SendUserFile`, or what the host offers), never through GitHub. The
   owner applies the patch in the advisory's temporary private fork. The
   reporter and the collaborators the owner adds review it there.
5. **The owner merges** the fork's PR into `main` from the advisory. Merging
   is the owner's action (owner ruling, 2026-08-31). Never merge, and never
   enable auto-merge.
6. **After the merge, before the release.** The fix is public from the merge
   on. The owner, or a session the owner asks, checks that CI on `main` is
   green at the merge commit. A red run is fixed at once by a normal PR into
   `main`, worded neutrally until the advisory is published. No `v*` tag goes
   on a commit whose CI is red.
7. **Release, then publish.** The release follows the `release` skill as
   usual; its PR and notes stay neutral until the advisory is out. The tag is
   the owner's action. So are publishing the advisory and requesting a CVE,
   after the fixed release is out.

## Why it is shaped this way

- The advisory's temporary private fork is the one private place GitHub
  offers for the fix, and nothing automated runs there.
- `main` must be releasable at every commit (owner ruling, 2026-09-30), the
  advisory merge included. With no CI before that merge, the local checks of
  step 2 keep it so. They are not optional. A check that could not run is
  named in the report, so the owner can decide before merging.
