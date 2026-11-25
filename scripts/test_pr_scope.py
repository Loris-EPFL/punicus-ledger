#!/usr/bin/env python3
"""Tests of the rules in .github/workflows/pr-scope.yml: which paths build which legs, on a PR and
in the merge queue.

The script lives in the workflow, so its job needs no checkout. This test reads it out of the
YAML (the lines between `python3 - <<'PY'` and `PY`), loads it without running main(), and
tests decide() and queue_pr(), which take no I/O. Standard library only; ci.yml runs it on every PR.

    python3 -I scripts/test_pr_scope.py
"""

import textwrap
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "pr-scope.yml"


def load_script():
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "python3 - <<'PY'")
    end = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "PY")
    namespace = {"__name__": "pr_scope"}
    exec(compile(textwrap.dedent("\n".join(lines[start + 1 : end])), str(WORKFLOW), "exec"), namespace)
    return namespace


SCOPE = load_script()
NOTHING = {"code": False, "dmg": False, "ios_device": False, "msi": False}
EVERYTHING = {"code": True, "dmg": True, "ios_device": True, "msi": True}


def scope(*paths, labels=(), base="main", head="topic", expected=None, **cap):
    rows = [[path, ""] for path in paths]
    _, outputs, _ = SCOPE["decide"](set(labels), base, head, rows, len(rows) if expected is None else expected, **cap)
    return outputs


def only(**flags):
    return {**NOTHING, "code": True, **flags}


class Docs(unittest.TestCase):
    def test_docs_alone_build_nothing(self):
        for path in ("docs/architecture.md", "docs/screenshots/home/default.png", ".claude/rules/ui.md",
                     ".claude/skills/pr/SKILL.md", "website/index.html", "README.md", "CHANGELOG.md",
                     "LICENSE", ".github/claude-review-prompt.md"):
            with self.subTest(path=path):
                self.assertEqual(scope(path), NOTHING)

    def test_markdown_in_a_source_set_is_code(self):
        self.assertTrue(scope("ui/src/commonMain/resources/notes.md")["code"])

    def test_an_unknown_path_is_code(self):
        self.assertTrue(scope("something/new.txt")["code"])


class Code(unittest.TestCase):
    def test_module_sources_build_the_ios_jobs_and_the_apks_only(self):
        for path in ("ui/src/commonMain/kotlin/eth/serotine/ui/Home.kt",
                     "data/src/commonMain/kotlin/eth/serotine/data/engine/MyotisHost.kt",
                     "domain/src/commonMain/kotlin/eth/serotine/domain/model/Address.kt",
                     "android-app/src/main/AndroidManifest.xml", "scripts/screenshots.py",
                     ".github/workflows/ci.yml"):
            with self.subTest(path=path):
                self.assertEqual(scope(path), only())


class Build(unittest.TestCase):
    def test_the_build_builds_everything(self):
        for path in ("gradle/libs.versions.toml", "gradle/verification-metadata.xml",
                     "gradle/wrapper/gradle-wrapper.properties", "build.gradle.kts", "settings.gradle.kts",
                     "data/build.gradle.kts", "ui/gradle.lockfile", "settings-gradle.lockfile",
                     "gradle.properties", "gradlew", "gradlew.bat",
                     "native/privacy-pools/Cargo.toml", "native/privacy-pools/src/lib.rs",
                     "native/privacy-pools/Cargo.lock"):
            with self.subTest(path=path):
                self.assertEqual(scope(path), {**EVERYTHING, "msi": path in ("gradle/libs.versions.toml",
                                                                             "build.gradle.kts")})

    def test_this_workflow_builds_everything(self):
        self.assertEqual(scope(".github/workflows/pr-scope.yml"), EVERYTHING)


class Dmg(unittest.TestCase):
    def test_the_desktop_packaging_builds_the_dmg(self):
        for path in ("desktop-app/src/main/kotlin/eth/serotine/desktop/Tray.kt",
                     ".github/workflows/desktop-dmg.yml", "scripts/gen_app_icons.java",
                     "android-app/src/main/res/drawable/ic_launcher_foreground.xml",
                     "android-app/src/main/res/values/ic_launcher_background.xml"):
            with self.subTest(path=path):
                self.assertEqual(scope(path), only(dmg=True))

    def test_other_android_resources_do_not(self):
        self.assertFalse(scope("android-app/src/main/res/values/strings.xml")["dmg"])


class IosDevice(unittest.TestCase):
    def test_the_ios_host_and_cinterop_build_the_device_link(self):
        for path in ("ios-app/xcode/project.yml", "ios-app/src/iosMain/kotlin/eth/serotine/ios/Main.kt",
                     ".github/workflows/ios.yml", "data/src/nativeInterop/cinterop/sodium.def"):
            with self.subTest(path=path):
                self.assertEqual(scope(path), only(ios_device=True))


class Msi(unittest.TestCase):
    def test_the_paths_the_msi_was_filtered_on(self):
        self.assertEqual(scope(".github/workflows/desktop-windows-msi.yml"), only(msi=True))
        self.assertEqual(scope("desktop-app/src/main/kotlin/eth/serotine/desktop/Main.kt"), only(dmg=True, msi=True))
        self.assertEqual(scope("desktop-app/build.gradle.kts"), EVERYTHING)

    def test_other_desktop_sources_do_not(self):
        self.assertFalse(scope("desktop-app/src/main/kotlin/eth/serotine/desktop/Tray.kt")["msi"])
        self.assertFalse(scope("ui/build.gradle.kts")["msi"])


class WholePr(unittest.TestCase):
    def test_any_path_decides(self):
        self.assertEqual(scope("docs/a.md", "desktop-app/build.gradle.kts"), EVERYTHING)

    def test_a_rename_counts_by_its_old_path_too(self):
        _, outputs, _ = SCOPE["decide"](set(), "main", "topic", [["docs/icon.md", "desktop-app/icon.md"]], 1)
        self.assertTrue(outputs["dmg"])


class EverythingBuilds(unittest.TestCase):
    def test_the_label(self):
        self.assertEqual(scope("docs/a.md", labels=["full-ci"]), EVERYTHING)

    def test_another_label_changes_nothing(self):
        self.assertEqual(scope("docs/a.md", labels=["documentation"]), NOTHING)

    def test_the_final_pr_of_a_plan(self):
        self.assertEqual(scope("docs/a.md", head="feature/privacy-pools"), EVERYTHING)

    def test_a_part_pr_into_its_feature_branch_is_scoped(self):
        self.assertEqual(scope("docs/a.md", base="feature/privacy-pools", head="part-2"), NOTHING)

    def test_a_list_that_does_not_match_the_count_warns(self):
        reason, outputs, warn = SCOPE["decide"](set(), "main", "topic", [["docs/a.md", ""]], 2)
        self.assertEqual((outputs, warn), (EVERYTHING, True), reason)

    def test_an_empty_list(self):
        self.assertEqual(scope(expected=0), EVERYTHING)

    def test_the_api_cap(self):
        paths = [f"docs/{n}.md" for n in range(3000)]
        self.assertEqual(scope(*paths), EVERYTHING)

    def test_the_comparison_cap(self):
        paths = [f"docs/{n}.md" for n in range(300)]
        self.assertEqual(scope(*paths, cap=300), EVERYTHING)
        self.assertEqual(scope(*paths[:299], cap=300), NOTHING)


class MergeQueue(unittest.TestCase):
    SHA = "0123456789abcdef0123456789abcdef01234567"

    def test_the_pr_number_of_a_queue_branch(self):
        self.assertEqual(SCOPE["queue_pr"](f"refs/heads/gh-readonly-queue/main/pr-365-{self.SHA}"), "365")

    def test_any_other_ref_has_none(self):
        for ref in ("", "refs/heads/main", f"refs/heads/gh-readonly-queue/main/pr-x-{self.SHA}",
                    "refs/heads/gh-readonly-queue/main/pr-365-abc", f"refs/heads/topic/pr-365-{self.SHA}",
                    f"refs/heads/gh-readonly-queue/main/pr-365-{self.SHA}\n"):
            with self.subTest(ref=ref):
                self.assertIsNone(SCOPE["queue_pr"](ref))


if __name__ == "__main__":
    unittest.main()
