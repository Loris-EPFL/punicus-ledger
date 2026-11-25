# The Punicus website

The public site of Punicus: what the wallet is, in plain words, for people who
are not developers; where to download it; and a guide to adding accounts and
Safes. Its public address is <https://punicus.eth.limo/> (the owner's,
2026-11-25: `punicus.eth` through the eth.limo gateway), which the
repository's links to the site use. Published to GitHub Pages by
`.github/workflows/website.yml`; how `punicus.eth` gets each build is below.

| File | What it is |
|---|---|
| `index.html` | the front page: what Punicus does, the app's screens, keys and privacy, the downloads |
| `guide.html` | getting started: create or import an account, watch an address, add or create a Safe, connect a web app over WalletConnect |
| `404.html` | the page for an address that does not exist |
| `css/site.css` | the one stylesheet, light and dark |
| `fonts/` | Atkinson Hyperlegible Next and Mono, with their licence (`OFL.txt`) |

The build script is `scripts/build_website.py`, its tests
`scripts/test_build_website.py`.

## Look at it

The pages are plain HTML and CSS. Open `website/index.html` in a browser from
a checkout: the screenshots and the logo are read from `docs/screenshots/` and
`assets/` where they are.

To see it as it is published, build it and open the result:

```bash
python3 scripts/build_website.py                 # into build/website/
python3 scripts/build_website.py --releases r.json   # with the newest release's files linked
gh api "repos/VesperBats/punicus/releases?per_page=30" > r.json   # how to get r.json
```

## What the build does

- **It copies in what the pages show.** A page names an app screen as
  `../docs/screenshots/<screen>/<state>.png`. CI renders those images and is
  their only source (`.claude/rules/ui.md`), so the site never keeps a copy
  of its own. The build copies the ones the pages name into the site and
  re-points the references. A page that names a screen or a state that no
  longer exists fails the build. The workflow builds on every pull request,
  so the pull request that renames a state is the one that finds out.
- **It links the newest release's files.** With `--releases`, the block
  between `<!-- downloads:start -->` and `<!-- downloads:end -->` in
  `index.html` becomes a button per file. Without it, the committed block
  stays: a link to the releases page.
- **It draws the favicon** from `assets/serotine_logo.svg`.
- **It checks the result**: every link, image and `#anchor` inside the site
  resolves, and the rules below hold.

It replaces the folder it builds into, and refuses one that holds the
repository, the site's source, or files that are not an earlier build. An
earlier build is known by the `.punicus-website` file the build leaves in it,
which is not published.

## Rules of the site

- **Self-contained.** Every file a page loads is part of the site: no CDN, no
  web-font service, no analytics. A visitor's browser talks to the host that
  serves the site and to nobody else. The build fails on a file loaded from
  elsewhere.
- **No script.** No `<script>`, no `on…` handler, no `ping`: a page that runs
  nothing can contact nobody once it is open. The build fails on each.
- **No request to GitHub from the visitor's browser.** The release's files are
  looked up when the site is built, not when it is read.
- **Relative paths only.** No path starts with `/`, and a page is linked by
  its file name (`guide.html`, never a folder). The site then works under
  GitHub Pages' `/punicus/` path, under a Swarm hash and at the root of an
  ENS gateway alike. The build fails on a path from the root.
- **It says what the app does, not more.** The claims on the pages follow
  `README.md`, `docs/wallet-ux.md` and `docs/privacy-policy.md`. A page that
  describes a feature is updated with the feature. What peers and the three
  optional services see is said as the privacy policy says it.
- **The screens are the app's own**, from `docs/screenshots/`. Never a mock-up
  and never an edited image. A screen may be cut off below its content
  (`class="crop"`, with the number of rows to show in `--rows`), and a
  step may point at a spot on it with an outline drawn over the image
  (`class="mark"`).
- **The pages show the phone app.** The previews are rendered on the desktop,
  where screen capture cannot be blocked, so the screens that show a recovery
  phrase or take the password end in a notice saying so. The desktop and iOS
  apps show that notice; the Android app blocks capture and shows none. Those
  screens are cut above the notice. When such a screen's layout changes, its
  `--rows` in `guide.html` has to follow.
- **A line of the app that is out of date is not published.** Two screens end
  in one today, and are cut above it: Import recovery phrase ("It cannot send
  transactions yet") and Connect a dApp ("Only on Sepolia for now"). Once the
  app's text is corrected, the cut can go.
- **The certificate digest** in the download section is the one pinned in
  `android-app/release-signing-cert.sha256`. The build fails when they differ.

## Publishing

**GitHub Pages.** The workflow publishes `main` on every push, once a day (a
release's files appear after its tag, with no push to `main`), and when
started by hand. Pages has to be turned on once, by the owner: Settings >
Pages > Source: *GitHub Actions*. Until then the site is built and not
published, and the run says so.

The page links the highest version that carries every published platform's
files. A release whose files are still being uploaded waits behind the
last complete one. A release candidate (`vX.Y.Z-rc…`) is not linked while any
other release is: the page is for people who are not developers. Whether a
candidate should be offered there is open, and the owner's to decide. The
run names each newer release it did not link, with the reason, as a warning.

**`punicus.eth`: how the name gets each build is not recorded here; open,
and the owner's.** It is the site's public address (the owner's, 2026-11-25),
but the workflow publishes to GitHub Pages alone (`vesperbats.github.io/punicus`).
Until the name is fed from the workflow's artifact, or by hand after every
release, its copy may name an older release than the Pages copy. A build for
it takes `--base-url https://punicus.eth.limo/`, so that its preview image's
URL is absolute. If it is fed through Swarm, a sketch of the steps, to be
checked against the tools' current documentation:

```bash
python3 scripts/build_website.py --releases r.json --base-url https://punicus.eth.limo/
swarm-cli upload build/website --stamp <postage batch id> \
  --index-document index.html --error-document 404.html
# then set the content hash of punicus.eth to the Swarm reference it prints
```

Both steps spend from a wallet (the postage stamp, the ENS transaction), so
they are the owner's. A feed would let the ENS record stay the same across
updates.

## The fonts

Atkinson Hyperlegible Next (text) and Atkinson Hyperlegible Mono (addresses,
commands), by the Braille Institute, under the SIL Open Font License 1.1
(`fonts/OFL.txt`). The typeface was drawn so that similar characters cannot be
mistaken for one another, such as `0` and `O`, or `1`, `l` and `I`, which is
what a page that shows addresses needs. The two files are the Latin subsets
of the variable fonts, taken unchanged from the npm packages
`@fontsource-variable/atkinson-hyperlegible-next` and
`@fontsource-variable/atkinson-hyperlegible-mono`, both 5.3.0:

```
18b2a1a39a2fa298b0ba5390aca68462669826c90925656f1c1f6796e0e1bbaf  atkinson-hyperlegible-next-latin-wght-normal.woff2
2706b1ee4f452e744ea91f7e4908cbde9c5d35521bf5ffffc71a382a2de89613  atkinson-hyperlegible-mono-latin-wght-normal.woff2
```
