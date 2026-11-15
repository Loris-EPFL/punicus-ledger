---
name: security-fix
description: "Fixing a vulnerability that is not public yet: a report from SECURITY.md (a private advisory), or one the owner hands over in private. Replaces Workflow steps 3 to 5: no PR and no push to any branch before the owner merges, the CI checks run locally, a patch handed to the owner for the advisory's private fork, neutral wording until the advisory is published. The owner merges, releases and publishes the advisory. Use before any work on such a report."
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
patch and report sent to the owner (step 5).

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
again (steps 7 to 9 need them), but every one of them stays neutral:

- A branch name, commit message, code comment, PR, changelog entry or
  release note says what changed ("check X before Y"). It never says that it
  was a vulnerability, how it is exploited, what it exposes or who reported
  it.
- The release PR and its `CHANGELOG.md` entry follow the `release` skill
  as usual, in that neutral wording.

## Steps

1. **Check that the build runs here, before you code.** Run `./gradlew help`
   (or a quick `./gradlew :domain:desktopTest`). Some cloud environments
   cannot reach Google's Maven repository, and Gradle does not configure
   there. If it cannot run, say so to the owner at once: the fix is then
   built and tested where Gradle runs (a local session, the owner's machine,
   or an environment that reaches Google's Maven) before the patch goes into
   the private fork.
2. **Code** the fix on a local branch off the current `main`. Do not push it.
3. **Run what CI would run, locally.** GitHub runs no CI and no status checks
   in an advisory's temporary private fork, and no integration reaches it,
   the CI reviewer included. A PR into `main` is checked by more than
   `ci.yml`, so run each that applies:
   - `ci.yml`: `./gradlew build`;
     `python3 scripts/check_security_invariants.py` and its tests
     (`python3 -m unittest discover -s scripts -p 'test_check_security_invariants.py'`).
   - A test that fails without the fix and passes with it.
   - `android-apk.yml`: the minSdk 29 gate, which runs nowhere else.
     `./gradlew build` does not scan the APKs. Run
     `python3 scripts/check_apk_min_api.py --apk <apk>` on the debug and the
     release APK.
   - A fix that adds or bumps a dependency: `scripts/update_gradle_pins.sh`
     pins this host only. The pins of the other build hosts come from the
     `gradle pins` run on a PR, which an embargoed fix does not get, and a
     host the metadata misses fails its first build. Name those hosts as not
     pinned.
   - A fix that changes a screen: render its previews locally (the
     `screenshots` skill) for the report. **The patch carries no file under
     `docs/screenshots/`** (owner rulings, 2026-11-04). The Screenshots run on
     `main` is then red by construction after the merge; step 7 fixes it.
   - `ios.yml` and the dmg and msi builds need macOS or Windows: name them as
     not run unless the session is on one.
4. **Review** the diff with `/code-review`, locally (never `--comment`), and
   fix what it finds. Check it against `.claude/rules/trust.md` as a reviewer
   would.
5. **Hand it to the owner.** Write the patch to an ignored path, never the
   checkout's root, where a later `git add -A` would publish it:
   `git format-patch -o build/security-fix origin/main`. Write a short report
   beside it. It opens with **"not built"** if step 1 found no Gradle. Then:
   the commit of `main` the patch is based on (the one the checks ran on; if
   `main` has moved, they no longer cover what is merged), what the fix does,
   every check of step 3 as passed, failed, or not run with the reason, and
   what step 7 will have to do (the Screenshots follow-up, unpinned hosts).
   In a cloud session the container is not the owner's machine: send both
   with the host's file tool (`SendUserFile`, or what the host offers), never
   through GitHub. The owner applies the patch in the advisory's temporary
   private fork. The reporter and the collaborators the owner adds review it
   there.
6. **The owner merges** the fork's PR into `main` from the advisory. Merging
   is the owner's action (owner ruling, 2026-08-31). Never merge, and never
   enable auto-merge.
7. **After the merge, before the release.** The fix is public from the merge
   on. The owner, or a session the owner asks, checks every run on the merge
   commit, not Build and Test alone: `ci.yml`, `android-apk.yml`,
   `screenshots.yml`, `ios.yml`, `gradle-pins.yml` and the three
   `desktop-*.yml`. A red run is fixed at once by a normal PR into `main`,
   in the neutral wording above. For a changed screen, that is a PR from a
   new branch off `main`, whose Screenshots run commits the render (the
   `screenshots` skill, "does not match the render of main"); its
   before/after table is public before the advisory is, so it too is worded
   neutrally. No `v*` tag goes on a commit whose runs are red.
8. **Release.** The release follows the `release` skill, in the neutral
   wording above. The tag is the owner's action. The fix's PR lives in the
   advisory's fork, so its changelog entry has no PR number to cite; it cites
   the merge commit instead.
9. **Publish.** Publishing the advisory, and requesting a CVE, are the
   owner's actions, after the fixed release is out. Then a normal PR adds the
   advisory's id (`GHSA-…`) to that release's entry under Security, for the
   owner to merge.

## Why it is shaped this way

- The advisory's temporary private fork is the one private place GitHub
  offers for the fix, and nothing automated runs there.
- `main` must be releasable at every commit (owner ruling, 2026-09-30), the
  advisory merge included. With no CI before that merge, the local checks of
  step 3 are what keep it so, and they are not optional. What they cannot
  cover (a changed screen's render, unpinned hosts, the macOS and Windows
  builds) is named in the report, so the owner decides before merging, and
  step 7 closes it before any tag.
