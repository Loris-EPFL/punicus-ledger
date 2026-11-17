"""Tests of scripts/build_website.py: the borrowed files, the downloads, the checks, and the site itself.

Run before the script in the workflow: `python3 -m unittest discover -s scripts -p 'test_build_website.py'`.
The last test builds the committed `website/` from this checkout, so a page that names a screenshot
that is gone, or links to an anchor that is not there, fails here.
"""

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build_website  # noqa: E402
from build_website import BuildError  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
DOWNLOADS = "https://github.com/biafra23/punicus/releases/download"


def temporary_folder(test):
    """A folder that is removed when `test` ends (`enterContext` needs Python 3.11; this runs on 3.9)."""
    folder = tempfile.TemporaryDirectory()
    test.addCleanup(folder.cleanup)
    return Path(folder.name)


def release(tag, published_at, names, draft=False):
    """A release as GitHub's list-releases API gives it, with the fields the build reads."""
    return {
        "tag_name": tag,
        "draft": draft,
        "prerelease": True,
        "published_at": published_at,
        "assets": [
            {"name": name, "size": 74_527_209, "browser_download_url": f"{DOWNLOADS}/{tag}/{name}"} for name in names
        ],
    }


V5_FILES = [
    "Punicus-v0.5.0-android-debug.apk",
    "Punicus-v0.5.0-android.apk",
    "Punicus-v0.5.0-arm64.deb",
    "Punicus-v0.5.0-arm64.dmg",
    "Punicus-v0.5.0-x64.deb",
    "Punicus-v0.5.0-x64.dmg",
]
V5 = release("v0.5.0", "2026-11-16T13:40:42Z", V5_FILES)


class RewriteBorrowedTest(unittest.TestCase):
    def test_a_page_at_the_root_gets_its_screenshot_and_asset_copied_in(self):
        text, copies = build_website.rewrite_borrowed(
            '<img src="../docs/screenshots/send/review.png"><img src="../assets/serotine_logo.svg">', "index.html"
        )
        self.assertEqual(text, '<img src="screenshots/send/review.png"><img src="img/serotine_logo.svg">')
        self.assertEqual(
            copies,
            {
                "screenshots/send/review.png": "docs/screenshots/send/review.png",
                "img/serotine_logo.svg": "assets/serotine_logo.svg",
            },
        )

    def test_a_file_in_a_folder_is_re_pointed_from_where_it_lands(self):
        text, copies = build_website.rewrite_borrowed('url("../../assets/serotine_logo.svg")', "css/site.css")
        self.assertEqual(text, 'url("../img/serotine_logo.svg")')
        self.assertEqual(copies, {"img/serotine_logo.svg": "assets/serotine_logo.svg"})

    def test_a_reference_inside_the_site_is_left_alone(self):
        text, copies = build_website.rewrite_borrowed('url("../fonts/a.woff2") <a href="guide.html">', "css/site.css")
        self.assertEqual(text, 'url("../fonts/a.woff2") <a href="guide.html">')
        self.assertEqual(copies, {})

    def test_climbing_past_the_borrowed_folders_is_refused(self):
        with self.assertRaises(BuildError):
            build_website.rewrite_borrowed('<img src="../../assets/x.png">', "index.html")

    def test_the_full_stop_of_a_sentence_is_not_part_of_the_path(self):
        text, copies = build_website.rewrite_borrowed("the logo is ../assets/serotine_logo.svg.", "index.html")
        self.assertEqual(text, "the logo is img/serotine_logo.svg.")
        self.assertEqual(copies, {"img/serotine_logo.svg": "assets/serotine_logo.svg"})


def files_of(tag):
    """The names of a complete release's files for `tag`."""
    return [name.replace("v0.5.0", tag) for name in V5_FILES]


class PickReleaseTest(unittest.TestCase):
    def test_the_highest_version_wins_pre_release_or_not(self):
        older = release("v0.4.0", "2026-11-10T16:43:29Z", files_of("v0.4.0"))
        self.assertIs(build_website.pick_release([older, V5]), V5)

    def test_a_fix_of_an_older_line_published_later_does_not_replace_the_newer_version(self):
        hotfix = release("v0.4.1", "2026-11-20T00:00:00Z", files_of("v0.4.1"))
        self.assertIs(build_website.pick_release([hotfix, V5]), V5)

    def test_within_one_version_the_one_published_last_wins(self):
        candidate = release("v0.5.0-rc1", "2026-11-14T00:00:00Z", files_of("v0.5.0-rc1"))
        self.assertIs(build_website.pick_release([candidate, V5]), V5)
        hotfix = release("v0.5.0-hotfix1", "2026-11-18T00:00:00Z", files_of("v0.5.0-hotfix1"))
        self.assertIs(build_website.pick_release([V5, hotfix]), hotfix)

    def test_a_release_still_being_uploaded_waits_behind_the_last_complete_one(self):
        uploading = release("v0.6.0", "2026-12-01T00:00:00Z", ["Punicus-v0.6.0-x64.deb", "Punicus-v0.6.0-arm64.deb"])
        self.assertIs(build_website.pick_release([uploading, V5]), V5)
        self.assertIs(build_website.pick_release([uploading]), uploading)

    def test_a_release_candidate_is_not_linked_while_another_release_is(self):
        candidate = release("v0.6.0-rc1", "2026-12-01T00:00:00Z", files_of("v0.6.0-rc1"))
        self.assertIs(build_website.pick_release([candidate, V5]), V5)
        self.assertIs(build_website.pick_release([candidate]), candidate)
        hotfix = release("v0.5.0-hotfix1", "2026-11-18T00:00:00Z", files_of("v0.5.0-hotfix1"))
        self.assertFalse(build_website.is_candidate(hotfix))
        self.assertTrue(build_website.is_candidate(release("v0.3.0-RC2", "2026-11-04T00:00:00Z", ["a"])))

    def test_each_newer_release_that_is_not_linked_is_named_with_the_reason(self):
        unsigned = release("v0.6.0", "2026-12-01T00:00:00Z", [n for n in files_of("v0.6.0") if "android" not in n])
        candidate = release("v0.7.0-rc1", "2026-12-08T00:00:00Z", files_of("v0.7.0-rc1"))
        older = release("v0.4.0", "2026-11-10T16:43:29Z", ["Punicus-v0.4.0-x64.deb"])
        everything = [older, V5, unsigned, candidate]
        self.assertIs(build_website.pick_release(everything), V5)
        self.assertEqual(
            build_website.passed_over(everything, V5),
            [
                "v0.7.0-rc1 is newer than v0.5.0 and is not linked: it is a release candidate",
                "v0.6.0 is newer than v0.5.0 and is not linked: it lacks -android.apk",
            ],
        )
        self.assertEqual(build_website.passed_over([older, V5], V5), [])

    def test_a_draft_a_release_without_files_and_another_kind_of_tag_are_passed_over(self):
        draft = release("v0.6.0", "2026-12-01T00:00:00Z", files_of("v0.6.0"), draft=True)
        empty = release("v0.5.1", "2026-11-20T00:00:00Z", [])
        nightly = release("nightly", "2026-12-02T00:00:00Z", files_of("nightly"))
        self.assertIs(build_website.pick_release([draft, empty, nightly, V5]), V5)

    def test_no_release_is_none_and_anything_but_a_list_is_refused(self):
        self.assertIsNone(build_website.pick_release([]))
        with self.assertRaises(BuildError):
            build_website.pick_release({"message": "Not Found"})


class DownloadsTest(unittest.TestCase):
    def test_each_platform_links_its_own_files_and_never_the_debug_app(self):
        block = build_website.downloads_html(V5)
        self.assertIn("<strong>v0.5.0</strong>, published on 16 November 2026", block)
        for name in ("android.apk", "arm64.dmg", "x64.dmg", "x64.deb", "arm64.deb"):
            self.assertIn(f'href="{DOWNLOADS}/v0.5.0/Punicus-v0.5.0-{name}"', block)
        self.assertNotIn("android-debug.apk", block)
        self.assertIn("<small>75 MB</small>", block)

    def test_windows_says_it_is_not_published_until_a_release_carries_the_msi(self):
        self.assertIn("Not published yet", build_website.downloads_html(V5))
        with_msi = release("v0.6.0", "2026-12-01T00:00:00Z", ["Punicus-v0.6.0-x64.msi"])
        block = build_website.downloads_html(with_msi)
        self.assertIn(f'href="{DOWNLOADS}/v0.6.0/Punicus-v0.6.0-x64.msi"', block)
        self.assertNotIn("Not published yet", block)

    def test_a_platform_the_release_lacks_points_to_the_releases_page(self):
        desktop_only = release("v0.5.0", "2026-11-16T13:40:42Z", ["Punicus-v0.5.0-x64.deb"])
        block = build_website.downloads_html(desktop_only)
        self.assertIn("Not part of v0.5.0", block)
        self.assertIn(f'href="{build_website.RELEASES_URL}">Look on the releases page', block)

    def test_a_file_hosted_elsewhere_is_not_linked_and_values_are_escaped(self):
        odd = release('v1"><script>', "2026-11-16T13:40:42Z", ["Punicus-x-android.apk", "Punicus-x-x64.deb"])
        odd["assets"][0]["browser_download_url"] = "https://example.org/Punicus-x-android.apk"
        block = build_website.downloads_html(odd)
        self.assertNotIn("example.org", block)
        self.assertNotIn("<script>", block)

    def test_a_release_none_of_whose_files_can_be_linked_stops_the_build(self):
        moved = release("v0.5.0", "2026-11-16T13:40:42Z", ["Punicus-v0.5.0-android.apk"])
        moved["assets"][0]["browser_download_url"] = "https://github.com/someone/else/releases/download/v0.5.0/a.apk"
        with self.assertRaises(BuildError):
            build_website.downloads_html(moved)

    def test_the_block_replaces_what_stands_between_the_markers(self):
        page = f"before\n        {build_website.START}\n        old\n        {build_website.END}\nafter\n"
        out = build_website.put_downloads(page, "        new\n")
        self.assertEqual(
            out, f"before\n        {build_website.START}\n        new\n        {build_website.END}\nafter\n"
        )
        with self.assertRaises(BuildError):
            build_website.put_downloads("no markers", "new")


class CheckSiteTest(unittest.TestCase):
    def site(self, files):
        folder = temporary_folder(self)
        for name, text in files.items():
            (folder / name).parent.mkdir(parents=True, exist_ok=True)
            (folder / name).write_text(text, encoding="utf-8")
        return folder

    def test_a_sound_site_has_no_problems(self):
        out = self.site(
            {
                "index.html": '<link rel="stylesheet" href="css/site.css"><h2 id="a">A</h2>'
                '<a href="guide.html#b">g</a><a href="#a">a</a><a href="https://github.com/">gh</a>'
                '<picture><source srcset="img/dark.png"><img src="img/light.png"></picture>',
                "guide.html": '<h2 id="b">B</h2><a href="index.html#a">home</a>',
                "css/site.css": 'body { background: url("../img/light.png"); }',
                "img/light.png": "",
                "img/dark.png": "",
            }
        )
        self.assertEqual(build_website.check_site(out), [])

    def test_a_missing_file_a_missing_anchor_and_a_missing_page_are_each_named(self):
        out = self.site(
            {
                "index.html": '<img src="img/gone.png"><a href="guide.html#nowhere">g</a><a href="lost.html">l</a>',
                "guide.html": '<h2 id="b">B</h2>',
            }
        )
        problems = "\n".join(build_website.check_site(out))
        self.assertIn("`img/gone.png` is not a file of the site", problems)
        self.assertIn("`guide.html#nowhere` names an anchor", problems)
        self.assertIn("`lost.html` is not a file of the site", problems)

    def test_nothing_is_loaded_from_another_host_and_no_path_starts_at_the_root(self):
        out = self.site(
            {
                "index.html": '<script src="https://cdn.example/x.js"></script><img src="/img/a.png">'
                '<link rel="stylesheet" href="//fonts.example/css"><a href="http://example.org">x</a>',
                "css/site.css": '@font-face { src: url("https://fonts.example/a.woff2"); }',
                "img/a.png": "",
            }
        )
        problems = "\n".join(build_website.check_site(out))
        self.assertIn("`https://cdn.example/x.js` is on another host", problems)
        self.assertIn("`//fonts.example/css` is on another host", problems)
        self.assertIn("`https://fonts.example/a.woff2` is on another host", problems)
        self.assertIn("`/img/a.png` starts at the server's root", problems)
        self.assertIn("`http://example.org` is neither https nor a page of the site", problems)

    def test_the_other_ways_a_page_or_a_stylesheet_loads_a_file_are_seen_too(self):
        out = self.site(
            {
                "index.html": '<style>body { background: url(https://a.example/bg.png) }</style>'
                '<p style="background: url(\'https://b.example/p.png\')">x</p>'
                '<video poster="https://c.example/v.png"></video><form action="https://d.example/f"></form>'
                '<svg><use href="https://e.example/s.svg#x"/></svg><meta http-equiv="refresh" content="0; url=x">',
                "css/site.css": '@import "https://f.example/a.css";',
            }
        )
        problems = "\n".join(build_website.check_site(out))
        for host in "abcdef":
            self.assertIn(f"https://{host}.example/", problems)
        self.assertIn("<meta http-equiv=refresh>", problems)

    def test_a_page_carries_no_script_no_handler_and_no_ping(self):
        out = self.site(
            {
                "index.html": '<script>fetch("https://tracker.example/hit")</script><script src="js/app.js"></script>'
                '<img src="pixel.png" onerror="go()"><a id="a" href="#a" ping="https://tracker.example/ping">a</a>',
                "js/app.js": "",
                "pixel.png": "",
            }
        )
        problems = build_website.check_site(out)
        self.assertEqual(len([p for p in problems if "a <script>" in p]), 2)
        self.assertTrue(any("`onerror` on <img>" in p for p in problems))
        self.assertTrue(any("`ping` on <a>" in p for p in problems))


class FaviconTest(unittest.TestCase):
    def test_it_draws_the_logos_paths_centred_in_the_logos_own_box(self):
        icon = build_website.favicon('<svg viewBox="0 0 1024 544"><path fill="#000000" d="M1,2 Z"/></svg>')
        self.assertIn('<path d="M1,2 Z"/>', icon)
        self.assertIn('transform="translate(4.00 17.12) scale(0.05469)"', icon)
        tall = build_website.favicon('<svg viewBox="0 0 100 200"><path d="M1,2 Z"/></svg>')
        self.assertIn('transform="translate(18.00 4.00) scale(0.28000)"', tall)

    def test_it_refuses_a_logo_it_cannot_draw(self):
        for logo in (
            "<svg></svg>",
            '<svg><path d="M1,2 Z"/></svg>',
            '<svg viewBox="0 0 10 10"><g transform="scale(2)"><path d="M1,2 Z"/></g></svg>',
            '<svg viewBox="0 0 10 10"><path d="M1,2 Z"/><circle r="1"/></svg>',
        ):
            with self.assertRaises(BuildError):
                build_website.favicon(logo)


DIGEST = "ab" * 32


def made_up_repository(test):
    """The least a repository needs for the site to build, in a folder of its own: nothing to lose."""
    repo = temporary_folder(test) / "work" / "repo"
    files = {
        "website/index.html": f"<p>{DIGEST}</p>\n{build_website.START}\n<p>old</p>\n{build_website.END}\n",
        "website/css/site.css": "body { margin: 0; }\n",
        "android-app/release-signing-cert.sha256": f"# the pin\n{DIGEST}\n",
        "assets/serotine_logo.svg": '<svg viewBox="0 0 1024 544"><path d="M1,2 Z"/></svg>',
        "docs/screenshots/index.html": "the overview page CI writes",
        "docs/screenshots/send/review.png": "an image",
    }
    for name, text in files.items():
        (repo / name).parent.mkdir(parents=True, exist_ok=True)
        (repo / name).write_text(text, encoding="utf-8")
    return repo


def listing(folder):
    """Every file under `folder` with what it holds."""
    return {p.relative_to(folder).as_posix(): p.read_bytes() for p in folder.rglob("*") if p.is_file()}


class OutputFolderTest(unittest.TestCase):
    """What the build may replace, each clause on a made-up repository."""

    def setUp(self):
        self.repo = made_up_repository(self)
        self.before = listing(self.repo.parent)

    def refused(self, out):
        with self.assertRaises(BuildError):
            build_website.build(self.repo, out)
        self.assertEqual(listing(self.repo.parent), self.before)

    def test_the_repository_and_a_folder_around_it_are_refused_a_marker_in_them_or_not(self):
        self.refused(self.repo)
        self.refused(self.repo.parent)
        for folder in (self.repo, self.repo.parent):
            (folder / build_website.MARKER).write_text("left here by mistake", encoding="utf-8")
        self.before = listing(self.repo.parent)
        self.refused(self.repo)
        self.refused(self.repo.parent)

    def test_the_sites_source_and_a_folder_inside_it_are_refused_a_marker_in_them_or_not(self):
        site = self.repo / "website"
        self.refused(site)
        self.refused(site / "css")
        self.refused(site / "new")
        for folder in (site, site / "css"):
            (folder / build_website.MARKER).write_text("left here by mistake", encoding="utf-8")
        self.before = listing(self.repo.parent)
        self.refused(site)
        self.refused(site / "css")

    def test_a_folder_with_files_that_no_build_left_is_refused_an_index_html_or_not(self):
        self.refused(self.repo / "docs" / "screenshots")
        self.refused(self.repo / "assets")

    def test_a_file_is_refused(self):
        self.refused(self.repo / "assets" / "serotine_logo.svg")

    def test_a_new_folder_an_empty_one_and_an_earlier_build_are_built_into(self):
        new = self.repo / "build" / "website"
        build_website.build(self.repo, new)
        self.assertTrue((new / build_website.MARKER).is_file())
        (new / "stale.html").write_text("old", encoding="utf-8")
        build_website.build(self.repo, new)
        self.assertFalse((new / "stale.html").exists())
        self.assertTrue((new / "index.html").is_file())
        empty = self.repo / "build" / "empty"
        empty.mkdir()
        build_website.build(self.repo, empty)
        self.assertTrue((empty / "index.html").is_file())


class MainTest(unittest.TestCase):
    def run_main(self, releases, environment):
        repo = made_up_repository(self)
        (repo / "releases.json").write_text(json.dumps(releases), encoding="utf-8")
        printed = io.StringIO()
        with mock.patch.dict(os.environ, environment), contextlib.redirect_stdout(printed):
            status = build_website.main(["--repo", str(repo), "--releases", str(repo / "releases.json")])
        return status, printed.getvalue()

    def test_a_release_passed_over_is_a_warning_in_a_workflow_run_and_a_note_elsewhere(self):
        unsigned = release("v0.6.0", "2026-12-01T00:00:00Z", [n for n in files_of("v0.6.0") if "android" not in n])
        line = "v0.6.0 is newer than v0.5.0 and is not linked: it lacks -android.apk"
        status, printed = self.run_main([V5, unsigned], {"GITHUB_ACTIONS": "true"})
        self.assertEqual(status, 0)
        self.assertIn(f"::warning::{line}\n", printed)
        status, printed = self.run_main([V5, unsigned], {"GITHUB_ACTIONS": ""})
        self.assertIn(f"build_website: note: {line}\n", printed)
        status, printed = self.run_main([V5], {"GITHUB_ACTIONS": "true"})
        self.assertNotIn("::warning::", printed)


class BuildTest(unittest.TestCase):
    """The committed site, built from this checkout."""

    def build(self, **arguments):
        out = temporary_folder(self) / "site"
        build_website.build(REPO, out, **arguments)
        return out

    def test_the_site_builds_and_carries_only_what_it_shows(self):
        out = self.build()
        index = (out / "index.html").read_text(encoding="utf-8")
        self.assertNotIn("../docs/", index)
        self.assertNotIn("../assets/", index)
        self.assertIn(build_website.RELEASES_URL, index)
        self.assertTrue((out / "guide.html").is_file())
        self.assertTrue((out / "favicon.svg").is_file())
        self.assertFalse((out / "README.md").exists())
        shown = {p.relative_to(out).as_posix() for p in (out / "screenshots").rglob("*.png")}
        pages = index + (out / "guide.html").read_text(encoding="utf-8")
        self.assertTrue(shown)
        for path in shown:
            self.assertIn(path, pages)

    def test_a_release_puts_its_files_on_the_page_and_the_base_url_into_the_preview_image(self):
        out = self.build(releases=[V5], base_url="https://example.org/punicus/")
        index = (out / "index.html").read_text(encoding="utf-8")
        self.assertIn(f"{DOWNLOADS}/v0.5.0/Punicus-v0.5.0-android.apk", index)
        self.assertIn('property="og:image" content="https://example.org/punicus/img/social-preview.png"', index)

    def test_the_page_names_the_pinned_release_certificate(self):
        index = (REPO / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn(build_website.pinned_certificate(REPO), index)


if __name__ == "__main__":
    unittest.main()
