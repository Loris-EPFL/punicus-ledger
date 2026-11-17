#!/usr/bin/env python3
"""Builds the Punicus website (`website/`) into one self-contained folder, standard library only.

    build_website.py [--repo DIR] [--out DIR] [--releases FILE] [--base-url URL]

The pages are plain HTML and CSS and open from a checkout as they are. What the build adds:

1. **The borrowed files.** A page shows the app's own screens, and the logo, from where the
   repository keeps them: `../docs/screenshots/<screen>/<state>.png` (CI's render, the only source
   of those images: `.claude/rules/ui.md`) and `../assets/`. The build copies each file a page or
   the stylesheet names into the site (`screenshots/`, `img/`) and re-points the reference, so the
   site carries only the images it shows. A reference to a file that does not exist fails the
   build: a screen or a state that was renamed is noticed here, not by a visitor.
2. **The downloads.** With `--releases` (the JSON of GitHub's "list releases" API), the block
   between `<!-- downloads:start -->` and `<!-- downloads:end -->` in `index.html` is replaced by
   the newest release's files, linked directly. Without it the block stays as committed: a link
   to the releases page. The page never asks GitHub anything in the visitor's browser.
3. **The favicon**, drawn from `assets/serotine_logo.svg`.
4. **The checks.** Every local link, image, font and `#anchor` of the built site resolves; nothing
   is loaded from another host, by a page or by a stylesheet; no path starts at the server's root;
   the Android certificate digest on the page is the one pinned in
   `android-app/release-signing-cert.sha256`. Together they keep the site hostable under any path
   with no server behind it: GitHub Pages' project path today, a Swarm hash or an ENS gateway
   later (`website/README.md`).

`--base-url` (the site's public address) makes the social preview image's URL absolute, as the
Open Graph protocol asks. Exit status 1 with a message per problem when a check fails.
"""

from __future__ import annotations

import argparse
import html
import json
import posixpath
import re
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from typing import Optional

SITE = "website"
# The repository folders a page may show files of, and where each lands in the built site.
BORROWED = {"docs/screenshots/": "screenshots/", "assets/": "img/"}
# A reference ends in a letter, a digit, `_` or `-`: the full stop of a sentence that names a path is not part of it.
BORROWED_REF = re.compile(r"(?:\.\./)+(?:docs/screenshots|assets)/[A-Za-z0-9_./-]*[A-Za-z0-9_-]")
TEXT_SUFFIXES = {".html", ".css"}
# Not part of the site: the folder's own notes.
LEFT_OUT = {"README.md"}

START = "<!-- downloads:start -->"
END = "<!-- downloads:end -->"
RELEASES_URL = "https://github.com/biafra23/punicus/releases"
DOWNLOAD_PREFIX = "https://github.com/biafra23/punicus/releases/download/"
CERT_PIN = "android-app/release-signing-cert.sha256"
LOGO = "assets/serotine_logo.svg"
MONTHS = (
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)  # fmt: skip


class BuildError(Exception):
    """One or more problems that stop the build, one per line."""


# --- the borrowed files --------------------------------------------------------------------------


def rewrite_borrowed(text: str, page: str) -> tuple[str, dict[str, str]]:
    """Re-points the references in `text` that leave the site for a borrowed repository folder.

    `page` is the file's path inside the site (`index.html`, `css/site.css`). Returns the new
    text and what to copy: the path inside the built site -> the path inside the repository. A
    reference that climbs out of the site to anywhere but a borrowed folder is an error.
    """
    copies: dict[str, str] = {}
    here = posixpath.dirname(posixpath.join(SITE, page))

    def replace(match: re.Match[str]) -> str:
        source = posixpath.normpath(posixpath.join(here, match.group(0)))
        for folder, lands_in in BORROWED.items():
            if source.startswith(folder):
                target = lands_in + source[len(folder) :]
                copies[target] = source
                return posixpath.relpath(target, posixpath.dirname(page) or ".")
        raise BuildError(f"{SITE}/{page}: `{match.group(0)}` is `{source}`, outside {sorted(BORROWED)}")

    return BORROWED_REF.sub(replace, text), copies


# --- the downloads -------------------------------------------------------------------------------


@dataclass(frozen=True)
class Platform:
    """One row of the downloads: its files by the end of their names, each with its button's text."""

    name: str
    about: str
    files: tuple[tuple[str, str], ...]
    # What the row says when the release carries none of its files; None: point to the releases page.
    missing: Optional[str] = None


PLATFORMS = (
    Platform(
        "Android",
        "Android 10 or newer. An APK file that you install yourself.",
        (("-android.apk", "Download for Android"),),
    ),
    Platform(
        "macOS",
        "Apple silicon is every Mac with an M1 chip or newer.",
        (("-arm64.dmg", "Apple silicon"), ("-x64.dmg", "Intel")),
    ),
    Platform(
        "Linux",
        "Packages for Debian and Ubuntu. x64 is what most PCs need.",
        (("-x64.deb", "x64"), ("-arm64.deb", "arm64")),
    ),
    Platform(
        "Windows",
        "An installer for Windows 10 and 11.",
        (("-x64.msi", "Download for Windows"),),
        missing="Not published yet. The Windows installer follows once the engine inside Punicus runs on Windows.",
    ),
)
# No release carries an iOS app (README.md, "Not built yet"), so its row is fixed text.
IPHONE = (
    '          <li>\n            <div>\n              <h3>iPhone</h3>\n'
    "              <p>A development build only for now. Punicus is not in the App Store yet.</p>\n"
    '            </div>\n            <div class="files later">Coming later</div>\n          </li>\n'
)


TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)(?:-.+)?")


def asset_names(release: dict) -> list[str]:
    """The names of the files `release` carries."""
    return [str(a.get("name", "")) for a in release.get("assets", []) if isinstance(a, dict)]


def is_complete(release: dict) -> bool:
    """Whether `release` carries every file of every platform that is published today.

    A tag's files are uploaded by several workflows, minutes apart, and a signing failure publishes
    no Android app at all (`.claude/rules/github-workflows.md`, *Releases*): a release that lacks
    one is not shown while an earlier one has them all.
    """
    names = asset_names(release)
    return all(
        any(name.endswith(ending) for name in names)
        for platform in PLATFORMS
        if platform.missing is None
        for ending, _ in platform.files
    )


def pick_release(releases: object) -> Optional[dict]:
    """The release the page links: the highest version that is complete; None when there is no release.

    Drafts and tags that are not `vX.Y.Z` or `vX.Y.Z-<suffix>` are left out. Pre-releases count:
    every release of Punicus so far is one (CHANGELOG.md). Releases are ordered by version, so a
    fix of an older line published later does not replace a newer one; within one version (an rc,
    then the release, then a hotfix) the one published last wins. When no release is complete,
    the highest version that carries any file is taken, and its rows say what it lacks.
    """
    if not isinstance(releases, list):
        raise BuildError("--releases: not the list GitHub's releases API returns")

    def order(r: dict) -> tuple:
        return (*(int(n) for n in TAG.fullmatch(r["tag_name"]).groups()), r["published_at"])

    usable = [
        r
        for r in releases
        if isinstance(r, dict)
        and not r.get("draft")
        and isinstance(r.get("published_at"), str)
        and isinstance(r.get("tag_name"), str)
        and TAG.fullmatch(r["tag_name"])
        and asset_names(r)
    ]
    complete = [r for r in usable if is_complete(r)]
    return max(complete or usable, key=order, default=None)


def long_date(published_at: str) -> str:
    """`2026-11-16T13:40:42Z` as `16 November 2026`."""
    moment = datetime.strptime(published_at[:10], "%Y-%m-%d")
    return f"{moment.day} {MONTHS[moment.month - 1]} {moment.year}"


def megabytes(size: object) -> str:
    """A file's size as whole megabytes, or nothing when the API named none."""
    return f"{max(1, round(size / 1_000_000))} MB" if isinstance(size, int) and size > 0 else ""


def downloads_html(release: dict) -> str:
    """The downloads block for `release`: its version and date, and a row per platform.

    Every value of the release is escaped, and only a file under this repository's own release
    downloads is linked: the JSON is input, not markup.
    """
    tag = str(release["tag_name"])
    assets = [a for a in release.get("assets", []) if isinstance(a, dict)]
    notes = f"{RELEASES_URL}/tag/{tag}"
    lines = [
        f'        <p class="release-line">The newest version is <strong>{html.escape(tag)}</strong>, published on '
        f'{long_date(str(release["published_at"]))}. <a href="{html.escape(notes, quote=True)}">'
        "What changed, and every file</a>.</p>\n",
        '        <ul class="downloads">\n',
    ]
    for platform in PLATFORMS:
        buttons = []
        for ending, label in platform.files:
            for asset in assets:
                name, url = str(asset.get("name", "")), str(asset.get("browser_download_url", ""))
                if name.endswith(ending) and url.startswith(DOWNLOAD_PREFIX):
                    size = megabytes(asset.get("size"))
                    small = f" <small>{size}</small>" if size else ""
                    buttons.append(
                        f'<a class="button" href="{html.escape(url, quote=True)}">{html.escape(label)}{small}</a>'
                    )
                    break
        if buttons:
            about, files = platform.about, '<div class="files">' + "".join(buttons) + "</div>"
        elif platform.missing:
            about, files = platform.missing, '<div class="files later">Coming later</div>'
        else:
            about = f"Not part of {tag}. An earlier version may have it."
            link = f'<a class="button quiet" href="{RELEASES_URL}">Look on the releases page</a>'
            files = f'<div class="files">{link}</div>'
        lines.append(
            f"          <li>\n            <div>\n              <h3>{html.escape(platform.name)}</h3>\n"
            f"              <p>{html.escape(about)}</p>\n            </div>\n            {files}\n          </li>\n"
        )
    lines += [IPHONE, "        </ul>\n"]
    if DOWNLOAD_PREFIX not in "".join(lines):
        raise BuildError(
            f"--releases: {tag} has no file under {DOWNLOAD_PREFIX}; after a rename of the repository, "
            "change RELEASES_URL and DOWNLOAD_PREFIX here, and the links in website/"
        )
    return "".join(lines)


def put_downloads(page: str, block: str) -> str:
    """`page` with what stands between the two markers replaced by `block`."""
    if page.count(START) != 1 or page.count(END) != 1 or page.index(START) > page.index(END):
        raise BuildError(f"index.html: needs `{START}` and `{END}`, once each and in that order")
    head, rest = page.split(START)
    return head + START + "\n" + block + "        " + END + rest.split(END)[1]


# --- the favicon ---------------------------------------------------------------------------------


def favicon(logo: str) -> str:
    """The logo's bat in white on the app's violet: readable on a light and on a dark tab strip."""
    paths = re.findall(r'<path\b[^>]*\bd="([^"]+)"', logo)
    box = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', logo)
    # Only what is copied below is understood: paths in the box's own coordinates. Anything else
    # would be drawn wrong without a word, so it stops the build instead.
    other = re.search(r"<(?:g|circle|ellipse|rect|polygon|polyline|line|use|image|text)\b|\btransform=", logo)
    if not paths or not box or other:
        raise BuildError(f"{LOGO}: the favicon is drawn from plain <path>s in a `0 0 w h` viewBox, which this is not")
    width, height = float(box.group(1)), float(box.group(2))
    # Scaled to fit a 56 x 56 square, centred in a 64 x 64 tile.
    scale = 56 / max(width, height)
    drawn = "".join(f'<path d="{d}"/>' for d in paths)
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
        '<rect width="64" height="64" rx="14" fill="#5d45a3"/>'
        f'<g fill="#fff" transform="translate({32 - width * scale / 2:.2f} {32 - height * scale / 2:.2f}) '
        f'scale({scale:.5f})">{drawn}</g>'
        "</svg>\n"
    )


# --- the checks ----------------------------------------------------------------------------------


REMOTE = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.IGNORECASE)
# What a stylesheet loads: `url(...)`, and an `@import` of a quoted address.
CSS_LOAD = re.compile(r"""url\(\s*['"]?([^'")]+)['"]?\s*\)|@import\s+['"]([^'"]+)['"]""")
# The attributes through which an element fetches a file, or sends the visitor's data somewhere.
LOADING_ATTRIBUTES = ("src", "href", "xlink:href", "data", "poster", "action", "formaction", "background")


def css_loads(css: str) -> list[str]:
    """The addresses a piece of CSS loads, `data:` ones left out: they are part of the file."""
    found = [a or b for a, b in CSS_LOAD.findall(css)]
    return [ref for ref in found if not ref.startswith("data:")]


class Page(HTMLParser):
    """The ids of a page, the files it loads and the links it offers.

    A link is an `<a href>`: where the visitor may choose to go. Everything else an element names
    is a load: the browser fetches it, or sends to it, without being asked.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.loads: list[str] = []
        self.links: list[str] = []
        self.refreshes = False
        self._in_style = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        values = {name: value or "" for name, value in attrs}
        if values.get("id"):
            self.ids.add(values["id"])
        self._in_style = tag == "style"
        self.loads += css_loads(values.get("style", ""))
        if tag == "meta" and values.get("http-equiv", "").lower() == "refresh":
            self.refreshes = True
        if tag == "a":
            if "href" in values:
                self.links.append(values["href"])
            return
        self.loads += [values[name] for name in LOADING_ATTRIBUTES if name in values]
        for candidate in values.get("srcset", "").split(","):
            if candidate.strip():
                self.loads.append(candidate.split()[0])

    def handle_endtag(self, tag: str) -> None:
        if tag == "style":
            self._in_style = False

    def handle_data(self, data: str) -> None:
        if self._in_style:
            self.loads += css_loads(data)


def check_site(out: Path) -> list[str]:
    """What is wrong with the built site in `out`, one line each; empty when it is sound."""
    problems: list[str] = []
    pages: dict[str, Page] = {}
    for path in sorted(out.rglob("*.html")):
        page = Page()
        page.feed(path.read_text(encoding="utf-8"))
        pages[path.relative_to(out).as_posix()] = page

    def local(name: str, ref: str, what: str) -> Optional[str]:
        """The file inside the site that `ref` on `name` names, or None after noting why not."""
        if REMOTE.match(ref):
            problems.append(f"{name}: {what} `{ref}` is on another host; the site loads nothing from elsewhere")
            return None
        if ref.startswith("/"):
            problems.append(f"{name}: {what} `{ref}` starts at the server's root; the site must work under any path")
            return None
        target = posixpath.normpath(posixpath.join(posixpath.dirname(name), ref.split("#")[0].split("?")[0]))
        if target.startswith("..") or not (out / target).is_file():
            problems.append(f"{name}: {what} `{ref}` is not a file of the site")
            return None
        return target

    for name, page in pages.items():
        if page.refreshes:
            problems.append(f"{name}: a <meta http-equiv=refresh> sends the visitor on without being asked")
        for ref in page.loads:
            local(name, ref, "the file")
        for ref in page.links:
            if re.match(r"^(?:https:|mailto:)", ref):
                continue
            if REMOTE.match(ref):
                problems.append(f"{name}: the link `{ref}` is neither https nor a page of the site")
                continue
            target = name if ref.startswith("#") else local(name, ref, "the link")
            anchor = ref.partition("#")[2]
            if target and anchor and target in pages and anchor not in pages[target].ids:
                problems.append(f"{name}: the link `{ref}` names an anchor that {target} does not have")
    for path in sorted(out.rglob("*.css")):
        name = path.relative_to(out).as_posix()
        for ref in css_loads(path.read_text(encoding="utf-8")):
            local(name, ref, "the file")
    return problems


def pinned_certificate(repo: Path) -> str:
    """The release certificate's SHA-256 as the repository pins it."""
    lines = (repo / CERT_PIN).read_text(encoding="utf-8").splitlines()
    digests = [line.strip() for line in lines if re.fullmatch(r"[0-9a-f]{64}", line.strip())]
    if len(digests) != 1:
        raise BuildError(f"{CERT_PIN}: expected one SHA-256, found {len(digests)}")
    return digests[0]


# --- the build -----------------------------------------------------------------------------------


def build(repo: Path, out: Path, releases: Optional[object] = None, base_url: Optional[str] = None) -> None:
    """Builds the site of `repo` into `out`, replacing what is there. Raises BuildError."""
    repo, out = repo.resolve(), out.resolve()
    site = repo / SITE
    if not (site / "index.html").is_file():
        raise BuildError(f"{site}: no index.html")
    # The folder is replaced, so it must be one this build may delete: not the checkout or a folder
    # around it, not the site's source or a folder inside it, and, when it exists, a build of the
    # site or an empty folder.
    if out == repo or out in repo.parents or out == site or site in out.parents:
        raise BuildError(
            f"--out {out}: the build replaces that folder, and it holds the repository or the site's source"
        )
    if out.exists() and (not out.is_dir() or (any(out.iterdir()) and not (out / "index.html").is_file())):
        raise BuildError(f"--out {out}: exists and is not an earlier build of the site; the build would delete it")
    if out.exists():
        shutil.rmtree(out)
    copies: dict[str, str] = {}
    for source in sorted(p for p in site.rglob("*") if p.is_file()):
        name = source.relative_to(site).as_posix()
        if name in LEFT_OUT:
            continue
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix not in TEXT_SUFFIXES:
            shutil.copyfile(source, target)
            continue
        text, borrowed = rewrite_borrowed(source.read_text(encoding="utf-8"), name)
        copies.update(borrowed)
        if name == "index.html":
            release = pick_release(releases) if releases is not None else None
            if release:
                text = put_downloads(text, downloads_html(release))
            if pinned_certificate(repo) not in text:
                raise BuildError(f"index.html: the certificate digest on the page is not the one in {CERT_PIN}")
        if base_url and source.suffix == ".html":
            text = re.sub(
                r'(<meta property="og:image" content=")(?![a-z]+:)([^"]+)"',
                lambda m: f'{m.group(1)}{base_url.rstrip("/")}/{m.group(2)}"',
                text,
            )
        target.write_text(text, encoding="utf-8")

    gone = [source for source in copies.values() if not (repo / source).is_file()]
    missing = [f"{source} (shown by a page) does not exist" for source in gone]
    if missing:
        raise BuildError("\n".join(sorted(missing)))
    for target, source in copies.items():
        (out / target).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / source, out / target)
    (out / "favicon.svg").write_text(favicon((repo / LOGO).read_text(encoding="utf-8")), encoding="utf-8")

    problems = check_site(out)
    if problems:
        raise BuildError("\n".join(problems))


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Build the Punicus website into one self-contained folder.")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--out", type=Path, default=None, help="default: <repo>/build/website")
    parser.add_argument("--releases", type=Path, default=None, help="JSON of GitHub's list-releases API")
    parser.add_argument("--base-url", default=None, help="the site's public address, for the social preview image")
    args = parser.parse_args(argv)
    out = args.out or args.repo / "build" / "website"
    try:
        releases = json.loads(args.releases.read_text(encoding="utf-8")) if args.releases else None
        build(args.repo, out, releases, args.base_url or None)
    except (BuildError, OSError, ValueError) as error:
        print(f"build_website: {error}", file=sys.stderr)
        return 1
    files = [p for p in out.rglob("*") if p.is_file()]
    print(f"build_website: {len(files)} files, {sum(p.stat().st_size for p in files) / 1_000_000:.1f} MB, in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
