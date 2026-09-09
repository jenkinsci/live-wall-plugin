# Security

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** A public issue about a Jenkins plugin
tells everyone running it exactly how to attack them, before there is a fix to upgrade to.

**Report it to the Jenkins security team**, by following
[How to report a security issue](https://www.jenkins.io/security/reporting/). That page is the
authoritative description of where to file and what happens next; reports made through it are
handled confidentially.

That is the only route to use, and deliberately the only one offered here. Reporting anywhere else —
including a GitHub private security advisory on this repository — cannot lead to a CVE being
assigned, because for Jenkins plugins only the Jenkins security team can do that. They also
coordinate the disclosure, the advisory and the release, none of which a maintainer can arrange
alone.

Please include the plugin version, your Jenkins version, and enough detail to reproduce it. You will
get an acknowledgement, and credit in the advisory unless you would rather not have it.

## Supported versions

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |

There is only one release so far. When that changes, fixes will land on the latest minor version.

## What the plugin actually ships

Worth knowing before reading the Security tab, because the number there is bigger than it looks.

The `.hpi` contains exactly one jar — `live-wall.jar`, this plugin's own classes — and **no
third-party code at all**. The [CVE scan](.github/workflows/cve-scan.yml) enforces zero known
vulnerabilities across that, on every pull request, and that is what "Live Wall has no known
vulnerabilities" means here.

The same workflow also reports on the *full* dependency tree, which is Jenkins core and the
libraries core brings with it — Jetty, Spring, and friends. Those findings are uploaded to code
scanning, so they appear in the Security tab. They are real, but they are not this plugin's to fix:
`provided` scope means your Jenkins supplies those libraries at runtime, at whatever versions your
Jenkins ships, whether or not Live Wall is installed. Nothing in this repository changes what a
given controller is exposed to.

What this repository *can* do is set the minimum Jenkins it will install on, and that is the lever
we use. [docs/quality-bar.md](docs/quality-bar.md) has the current numbers and explains the split in
full.

**The practical advice is the same either way: keep Jenkins itself up to date.** That fixes far more
than any plugin can.

## Things that are deliberate, and not vulnerabilities

- **`scripts/demo.sh` starts a Jenkins with no security whatsoever** — no setup wizard, no login,
  anonymous users with full permissions. That is the point of it: a disposable local controller you
  can fill with two hundred throwaway jobs without generating an API token first. It binds to
  localhost and [docs/demo.md](docs/demo.md) says not to expose it. It is a development tool and is
  never part of the plugin.
- **The wall's data endpoint respects permissions.** `…/view/<name>/wallData` returns only jobs the
  requester can already read, and the `include`, `exclude` and `sortBy` URL parameters can only ever
  narrow that set. If you find a way to make it return a job the caller cannot see, that *is* a
  vulnerability and we would like to hear about it.
- **Job names are treated as untrusted input** and are written with `textContent`, never
  `innerHTML`. A job name that renders as markup on the wall would be a vulnerability worth
  reporting.
