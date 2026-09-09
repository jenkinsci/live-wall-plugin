# Live Wall

[![CI](https://github.com/eduardocerqueira/jenkins-live-wall/actions/workflows/ci.yml/badge.svg)](https://github.com/eduardocerqueira/jenkins-live-wall/actions/workflows/ci.yml)
[![CVE scan](https://github.com/eduardocerqueira/jenkins-live-wall/actions/workflows/cve-scan.yml/badge.svg)](https://github.com/eduardocerqueira/jenkins-live-wall/actions/workflows/cve-scan.yml)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue.svg)](LICENSE)

**A Jenkins view that turns your jobs into a wall of big coloured tiles, built for the television in
the corner of the office.**

![A Live Wall of hexagonal tiles filling the screen. Three jobs building at the top left carry
moving stripes; the rest are green, amber and red, with a header counting them and a
clock.](docs/images/wall-hexagon.png)

*Hexagons interlocked into a honeycomb. Jobs building right now sort to the top left, and the
striped fill means Jenkins has no duration estimate to draw a progress bar from yet.*

## See it running

Forty seconds against a real controller: palettes and shapes changing on a live wall of 42 jobs,
saving the look, and the kiosk page a television would be pointed at.

https://github.com/user-attachments/assets/2a78178a-342e-46e0-a370-7cf2a3b915e9

*No player above? [Watch the demo](https://github.com/user-attachments/assets/2a78178a-342e-46e0-a370-7cf2a3b915e9)
— the inline video only renders on GitHub.*

---

## Why

Inspired by the excellent [Build Monitor plugin](https://plugins.jenkins.io/build-monitor-plugin/),
which pioneered the idea. Live Wall is a rethink of the same idea for one specific situation: a
large screen that nobody is standing next to.

That leads to three promises, and they are the whole design.

### Everything fits

There is no pagination, and there never will be. Tiles are measured against your actual screen and
scaled so that every job in the view is visible at the same time. A job you can only see half the
time is a job nobody sees.

### Colour carries the message

A tile has a job name and a colour. That is it. No commit messages, no durations, no avatars — at
four metres you cannot read them anyway, and they cost the space the job name needs.

The palettes are fully saturated rather than pastel, and the text colour on each tile is chosen from
the **measured contrast ratio** against its fill, so it stays legible whatever colours you pick —
including ones you invent.

### Motion means something

A building job fills up in step with its estimated duration, and falls back to indeterminate stripes
the moment it overruns that estimate. The animation never lies about how far along a build is.

![The same wall drawn as rounded rectangles with a small gap between them, every job legible from a
distance.](docs/images/wall-rounded.png)

*The same jobs as rounded rectangles. Ten shapes, six palettes, and an adjustable gap — the default
is zero, so the tiles meet and the wall reads as one surface.*

---

## Quick start

1. **Manage Jenkins → Plugins → Available plugins**, search for **Live Wall**, and install it.
2. **New View →** name it **→ Live Wall**, then pick your jobs the way you always do.
3. Point the television at **`…/view/<name>/wall`** — the kiosk page, with no Jenkins chrome on it
   at all. The **Full screen** button on the view page does the same for a browser you are already
   sitting in front of.

That is the whole setup. Everything else is optional, and every default is chosen to be the one you
would have picked.

Requires **Jenkins 2.555.3 or newer**, running on **Java 21 or newer**. Java 21 is a requirement of
Jenkins itself at this version, not of this plugin — core 2.555.3 is compiled to Java 21 bytecode,
so a Java 17 controller cannot run that Jenkins at all, with or without Live Wall.

Prefer to build it yourself, or want a throwaway Jenkins to try it on rather than your own?
[docs/building.md](docs/building.md) covers building and installing from source, and
[docs/demo.md](docs/demo.md) starts a disposable controller with the plugin and a spread of sample
jobs in one command.

---

## What you can change

| | |
| --- | --- |
| **Palettes** | Vivid · Neon · High contrast · Daylight · Colour-blind safe · Midnight, plus per-colour overrides on top of any of them |
| **Shapes** | Rectangle · Rounded · Square · Circle · Octagon · Hexagon · Diamond · Parallelogram · Chevron · Cross |
| **Animations** | Progress fill · Sweep · Stripes · Pulse · None |
| **Contents** | All jobs, or only problems — an alert board where an empty screen means everything is fine |
| **Order** | Running first (default) · Failed first · Passing first · Name (nothing ever moves) · Most recent · View order |
| **Which jobs** | Tick them in the job picker, or filter by name with `ci-*`, `*-pipeline` or just `drools` — wildcards and plain words, no regular expressions |
| **Labels** | Folder paths on or off, and a regex to strip the noise: `^ci-\|-pipeline$` turns `ci-decision-control-pipeline` into `decision-control`, and lets it be drawn twice as large |
| **Packing** | Interlocked by default, so hexagons tessellate into a honeycomb and diamonds into a lattice, with an adjustable gap (0 by default) and a hairline separator that keeps touching tiles countable |
| **Sizing** | Fit everything on one screen, or hold a readable size and scroll continuously |
| **Panel care** | Optional slow drift to protect OLED and plasma screens from burn-in |

Try any of it without saving by putting it in the URL — handy for a screen you cannot comfortably
type on:

```
…/view/pipelines/wall?palette=neon&shape=octagon&refresh=10&header=0
```

The view page also has a preview bar: drop-downs for palette, shape, animation, packing and order,
and sliders for the gap and the separator, all applied to your real jobs as you change them. Moving
one changes the wall in front of you and nothing else, so you can try a palette on the real thing
without committing to it — and **Save this look** then keeps what you arrived at, so the kiosk page
and full screen open with it too.

![The Live Wall view inside Jenkins. Above the wall, a row of controls: Full screen, Open kiosk
page, drop-downs for palette, shape, animation, packing and order, sliders for gap and separator,
and a Save this look button. Below them, a honeycomb of hexagonal tiles in the high-contrast
palette.](docs/images/view-page.png)

Every setting is documented in **[docs/configuration.md](docs/configuration.md)**.

---

## Accessibility and unattended operation

- The **colour-blind safe** palette uses blue and orange instead of green and red, and adds a hatch
  pattern to failing and unstable tiles, so status never depends on hue alone.
- Every animation is suppressed for viewers whose browser asks for reduced motion.
- Losing contact with Jenkins raises a banner instead of quietly showing stale colours.
- The clock in the header is a cheap way to tell at a glance that the screen has not frozen.
- A wall in a hidden browser tab backs off automatically, so a forgotten tab is not a load problem.

---

## How it works

The server renders an empty shell and then answers `…/view/<name>/wallData` with a small JSON
document — one object per job, carrying a label, a status, and raw timestamps for anything building.
Everything else happens in the browser, which is what lets a wall sit open for months without
reloading. That endpoint respects permissions: jobs you cannot read are not in it.

Three pieces of that browser code are worth knowing about, all in
[`wall.js`](src/main/resources/io/jenkins/plugins/livewall/wall.js):

| | |
| --- | --- |
| `fitColumns()` | Scores every possible column count by the size of the largest tile it would allow, and picks the winner. This is what replaces pagination. |
| `fitFontSize()` | Measures the real glyphs on a canvas and binary-searches the largest font size at which a job name still fits its tile, breaking at spaces, hyphens, underscores and slashes. |
| `inkFor()` | Computes the WCAG relative luminance of each status colour and picks black or white text from the contrast ratio. |

---

## Documentation

| | |
| --- | --- |
| [Trying it locally](docs/demo.md) | The one-command demo script, and how to drive it |
| [Building, testing, installing](docs/building.md) | Development loop, running the tests, installing into your own Jenkins |
| [Configuration](docs/configuration.md) | Every setting, URL parameters, JCasC, the JSON endpoint |
| [Contributing](docs/CONTRIBUTING.md) | Issues, pull requests, adding palettes and shapes |
| [Releasing](docs/releasing.md) | How continuous delivery works here, which labels ship a change, and why versions are not semver |
| [The bar](docs/quality-bar.md) | All tests passing and zero shipped CVEs, for every pull request — and why the security scan is two scans |

---

## Contributing

Issues and pull requests are welcome. Read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) first —
especially the short list of things this plugin deliberately does not do.

Every pull request must have **all tests passing** and **zero known vulnerabilities in anything the
plugin ships**. Both are enforced by CI, and the security scan also runs every Sunday morning,
because the code stops changing but the vulnerability database does not. See
[the bar](docs/quality-bar.md) for why the security scan is two scans rather than one.

---

## Licence

[Apache License 2.0](LICENSE).

The job picker in the view configuration form is adapted from Jenkins core
(`hudson/model/ListView/configure-entries.jelly`, MIT licensed). Thanks again to
[Build Monitor](https://plugins.jenkins.io/build-monitor-plugin/) for showing what a Jenkins
radiator should feel like.
