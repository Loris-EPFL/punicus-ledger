---
name: release
description: "Cutting a release of Punicus. One release PR with the version pin moved, the changelog entry, the engine-bump question, the Android release key, and what a v* tag publishes. The tag is the owner's. Use when the owner asks for a release, a version bump, a v* tag, or says 'cut a release'."
---

# Releases: ask before cutting one

When the user asks for a release (a `v*` tag, a version bump, "cut a
release"), everything a release needs goes into ONE release PR, and **the tag
is the owner's action** (owner rulings, 2026-09-02 and 2026-09-11 in Myotis,
carried over). After the merge the owner pushes it, or explicitly asks for it
to be pushed. Never push a `v*` tag on your own initiative: it is the publish
trigger for every asset workflow, and a stronger action than the merge that
is already forbidden.

## 1. Move the version pin. MANDATORY, not a question

(Owner ruling, 2026-09-24.) ONE pin: `version = "X.Y.Z-SNAPSHOT"` in the root
`build.gradle.kts` (`allprojects`). Everything else is derived from it:

- the Android `versionName` and `versionCode` (`releaseBuildNumber`,
  `X*1_000_000 + Y*1_000 + Z`);
- the deb and msi versions, and the dmg's `+1`-major version;
- the iOS app's version and build number (stamped by its Xcode build from
  `:printReleaseVersion`);
- the on-screen version.

Then verify with the SAME query the tag guard makes:

```bash
./gradlew -q :printReleaseVersion   # must print releaseVersion=X.Y.Z
```

`releaseVersion` must equal the tag's version core. The guard
(`release-version-guard.yml`, called by every workflow that uploads to a
release) accepts `vX.Y.Z` and `vX.Y.Z-<suffix>` (rc and hotfix shapes),
nothing else, and refuses the tag otherwise before anything is uploaded.

## 2. Add the release to CHANGELOG.md. MANDATORY, not a question

(Owner's request, 2026-11-09, #281.) `CHANGELOG.md` follows Keep a Changelog.
The release PR turns `## [Unreleased]` into `## [X.Y.Z] - <date of the PR>`
(the tag's version, its suffix included for a pre-release such as
`0.3.0-rc1`), starts a new empty `## [Unreleased]` above it, and updates the
compare links at the bottom. Check the entry against the merged PRs since the
last tag (`git log --first-parent <last tag>..main`): every change a user or a
packager would notice, under Added, Changed, Fixed, Removed or Security, with
its PR and issue numbers. The GitHub release notes may link to the entry.
Before you write it, ask the owner whether any merge since the last tag is an
embargoed security fix: its entry and the release notes then stay neutral
until the advisory is published (the `security-fix` skill, steps 8 and 9).
`git log <last tag>..origin/main --grep='^Merge pull request from GHSA-'`
lists the advisory merges, so the question does not rest on memory.

## 3. Bump the embedded Myotis engine? Ask

A Myotis release is published to its `maven-repo` branch. Moving the
`eth.myotis:myotis-engine` version is a dependency change like any other. It
goes through the release PR when the owner wants it in the release.

## 3b. Refresh the clear-signing descriptors. MANDATORY, not a question

(Owner's decision, 2026-11-25, #240, question 5: "a step of every release PR";
docs/clear-signing.md, *A1*.) In the release PR:

1. Move `REGISTRY_COMMIT` in `scripts/clear-signing/generate.py` to the
   registry's current head, and run `python3 -I scripts/clear-signing/fetch.py`.
2. `python3 -I scripts/clear-signing/generate.py --print-pins` gives the new
   `PINNED`. Read the diff of every vendored file that moved: a descriptor is
   reviewed in our PR (decision 4), its words as much as its paths.
3. Start a `myotis-rpcd` per network (Ethereum, Gnosis Chain, Sepolia) and run
   `python3 -I scripts/clear-signing/pin_code_hashes.py ETHEREUM=… GNOSIS=…
   SEPOLIA=…`. A moved code hash or implementation is a contract changed since
   the last release: say which, and why the new code may be described the same.
4. `python3 -I scripts/clear-signing/generate.py`, then commit what it writes.
   A refusal is fixed or the deployment is taken off the list, never ignored.

The release PR's description lists what moved, or says "the clear-signing
descriptors are unchanged".

## 3c. Refresh the verified ABIs

(How phase C is applied, the owner's to approve on #240: docs/clear-signing.md,
*Phase C*, "A refresh is a step of every release PR".) In the release PR:

1. `python3 -I scripts/verified-abis/fetch.py`. It stops at anything but an
   exact Sourcify match: move such a contract to `notPinned` in
   `contracts.json`, saying why, and run it again. Move a contract of
   `notPinned` whose match has become exact onto the list.
2. Steps 3 before this one where the list moved. Then `cd
   scripts/verified-abis && npm ci --ignore-scripts && node verify.mjs`:
   every contract recompiled, an exact match each. Read the diff of
   `verified.json`: a moved code hash or function is a contract changed.
3. With a `myotis-rpcd` per network, `python3 -I
   scripts/verified-abis/check_code_hashes.py --write ETHEREUM=… GNOSIS=…
   SEPOLIA=…` pins each contract's code hash from the chain in
   `contracts.json`, which `node verify.mjs` then holds the vendored code to.
4. `python3 -I scripts/verified-abis/generate.py`, then commit what it
   writes.

The release PR's description lists what moved, or says "the verified ABIs
are unchanged".

## 4. The Android release key

`sign-release` (android-apk.yml) signs the release APK on the tag with the
key in the `android-release` environment, and only for a tree with
`android-app/release-signing-cert.sha256`. Until the owner has set that up
(docs/android-release-signing.md), a tag publishes the desktop installers and
the debug APK, and NO release-signed Android app (owner's request,
2026-10-21). Once the pin is there, a signing failure publishes no Android
asset at all. Say which case applies in the release PR.

## What a tag publishes

Every `v*` tag publishes a pre-release with the signed Android APK (once the
release key is set up, step 4), the debug APK, the macOS dmgs (arm64 + x64)
and the Linux debs (x64; arm64 while the repository is public, the free
arm64 runner's condition). Each workflow creates the release race-tolerantly
and uploads its assets. The Windows msi is left out of tags until Myotis
ships a Windows engine library (owner's decision, 2026-10-21): it builds on
`main`, and a dispatch on a tag publishes it.

## The release PR

An ordinary single PR against `main`, under the Workflow (the `pr` skill),
with the version pin (step 1) and the changelog entry (step 2) in it.
Whatever is still on a feature branch is simply not in that release. The
merge is the owner's, and so is the tag.
