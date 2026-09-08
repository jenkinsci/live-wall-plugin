# Configuring a wall

Everything here is on the view's **Configure** page. Every field also has inline help in Jenkins —
this page is the same information in one place.

- [Jobs](#jobs)
- [Look](#look)
- [Contents](#contents)
- [Sizing](#sizing)
- [URL parameters](#url-parameters)
- [Configuration as code](#configuration-as-code)

## Jobs

The job picker, folder recursion, the include regex and job filters are the ones from a normal
Jenkins list view. A Live Wall *is* a list view underneath, so anything you already know about
choosing jobs for a view applies unchanged.

The Columns section is deliberately absent: a wall draws tiles, not a table.

### Only show jobs matching / But never these

A name filter for the wall, with no regular expression in sight. One pattern per line, or separated
by commas. Leave both blank and every job in the view gets a tile.

| Pattern | Matches |
| --- | --- |
| `drools` | any name **containing** `drools` — `drools`, `drools-nightly`, `ci-drools-pipeline` |
| `ci-*` | names **starting** with `ci-` |
| `*-pipeline` | names **ending** with `-pipeline` |
| `release-?` | `release-1`, but not `release-10` — `?` is exactly one character |

A plain word means "contains" because typing a word into a filter box and getting nothing back —
because it was not anchored — is the exact frustration this exists to avoid. Add a `*` or a `?` and
the whole name has to match instead.

Nothing else is special. A dot is a dot, `app(v2)` is a job called `app(v2)`. Matching ignores case,
and each pattern is tried against the job's full name, its display name and the label drawn on the
tile, so whichever of those you had in mind will work.

**But never these** is applied afterwards, and wins: a job matching an exclude is dropped even if it
also matched an include. Useful for carving the sandbox jobs out of an otherwise simple filter.

```
Only show jobs matching:   ci-*
                           drools
But never these:           *-sandbox
```

The view still *contains* every job you ticked; the filter decides which of them reach the wall.
Both fields also work as [URL parameters](#url-parameters), which is what lets one view feed several
screens: the office TV shows everything, the team's monitor adds `?exclude=dev-*`, and nobody has to
keep three near-identical views from drifting apart.

```
…/view/pipelines/wall?exclude=dev-*
…/view/pipelines/wall?include=ci-*&sort=status
```

Each override stands on its own — an `exclude` on the URL keeps whatever include list the view has
saved — and neither can reveal a job the viewer could not already see. The include regex from a
plain list view is still there under **Regular expression filter** if you already depend on it.

### Filtering on something other than the name

The two fields above only look at names, because that is what a wall usually needs. For anything
richer — build status, most recent build, SCM, parameters, job type, unclassified jobs, set
arithmetic between filters — install
[View Job Filters](https://plugins.jenkins.io/view-job-filters/). Its filters appear on this view's
configuration page and work here exactly as they do on a list view, because a Live Wall builds its
tiles from `ListView.getItems()`, which is what applies them.

They compose, and in this order: the job picker and the job filters decide which jobs the view
contains, then the name fields above narrow that down, then **Show** drops anything that is not a
problem if you asked it to. Nothing in this plugin duplicates what View Job Filters already does
well.

## Look

### Colour palette

| Palette | For |
| --- | --- |
| **Vivid** | The default. Saturated fills on near-black. |
| **Neon** | Glowing fills on pure black, for a dim room. |
| **High contrast** | Maximum separation and thick edges, for a very large open-plan floor. |
| **Daylight** | Light background, for a screen facing a window. |
| **Colour-blind safe** | Blue and orange instead of green and red, plus a hatch pattern on failing and unstable tiles so the wall reads without colour vision at all. |
| **Midnight** | Low glare, for a screen that stays on overnight. |
| **Custom** | The default colours, for you to override below. |

The text colour on each tile is **not** part of the palette. It is computed in the browser from the
WCAG contrast ratio of black and white against that tile's actual fill, so it stays legible whatever
colours end up there — including ones you invent.

### Override individual colours

Six fields — background, success, failure, unstable, aborted, and never-built-or-disabled. Each
takes a CSS hex colour (`#12d96a`) or a colour keyword (`tomato`). Anything else is rejected when
you save.

They apply **on top of whichever palette is selected**, so you can start from Daylight and change
only the red that bothers you. Leave one blank to keep the palette's own colour.

### Tile shape

`rectangle` · `rounded` · `square` · `circle` · `octagon` · `hexagon` · `diamond` ·
`parallelogram` · `chevron` · `cross`

Rectangles pack the most jobs onto a screen. Square, circle, diamond and cross keep their
proportions whatever shape their grid cell is, so they suit walls with room to spare. Shapes that
eat into the middle of a tile leave less room for the name, and the label shrinks to match
automatically.

### How tiles fit together

**Interlocked** (the default) offsets every other column or row and lets each shape overhang its
cell by exactly the amount its geometry needs, so the tiles tessellate into one continuous surface:
hexagons become a honeycomb, diamonds a lattice, parallelograms and chevrons a ribbon. **Grid** puts
every tile squarely in its own cell.

Rectangles, squares, octagons and crosses already tile edge to edge at zero gap and look the same
either way. Circles cannot tile at all, so they are left alone.

### Gap between tiles, and the separator inside them

Two different controls, and it is worth knowing which one you want.

| | |
| --- | --- |
| **Gap between tiles** | Empty background *between* tiles. **Zero by default**, which is what makes the wall read as one surface rather than a scattering of cards. Raise it if you would rather the tiles floated. |
| **Separator inside each tile** | A hairline of background colour drawn *inside* each tile's own outline, following the shape rather than its bounding box. Two pixels by default. |

The separator is what keeps zero gap usable. Without it, twenty passing jobs next to each other are
one green rectangle and you cannot tell how many jobs you are looking at. With it, they stay
countable while the wall still reads as a single surface — and on a honeycomb it looks like the wall
between cells. Go to four or five pixels on a 4K panel viewed from a long way away.

### In-progress animation

| | |
| --- | --- |
| **Progress fill** | The default. Fills the tile in step with the build's estimated duration, and falls back to moving stripes the moment the build overruns that estimate or has no estimate at all — so it never lies about how far along a build is. |
| **Sweep** | A light beam crossing the tile. |
| **Stripes** | Moving diagonal bars. |
| **Pulse** | A slow breathing glow. |
| **None** | A static bright edge and nothing else. |

Whatever you pick, a building tile always keeps a bright edge, and it always keeps the colour of its
last outcome underneath: a build running on a broken job still reads as broken.

All of these are suppressed for viewers whose browser asks for reduced motion.

### Drift the wall slowly to protect the panel

Moves the whole wall by a few pixels over about twenty minutes. Worth turning on for an OLED or
plasma panel showing the same thing every day. Pointless on an LCD.

## Contents

### Show

| | |
| --- | --- |
| **All jobs** | The default. |
| **Only problems** | Failing, unstable and aborted only. Turns the same view into an alert board where an empty screen means everything is fine — and it says so, rather than looking like a broken view. |
| **Problems and jobs building right now** | The above, plus anything in progress. |

### Order

| | |
| --- | --- |
| **Running first** | The default. Whatever is building right now rises to the top left, then anything queued, then the rest worst-first. The wall answers "what is happening" before "what is broken". |
| **Failed first** | Problems at the top left, regardless of what is building. |
| **Passing first** | The inverse, with failures at the bottom. |
| **Name** | Alphabetical, and the only ordering where nothing ever moves. Pick this if you would rather learn where each job lives than have the interesting one come to you. |
| **Most recently built first** | |
| **View order** | Whatever order the underlying view produces. |

The status-based orderings move tiles around as builds start and finish. That is the point of them,
but it does mean a job is not always in the same place — which is the trade-off **Name** exists to
avoid.

### The tick boxes

| | |
| --- | --- |
| **Hide disabled jobs** | Leaves them off entirely rather than drawing them dimmed. |
| **Show the header** | The strip with the view name, the failing/unstable/building counts and a clock. The clock is a cheap way to tell that the screen has not frozen. On by default. |
| **Include folder names in the tile label** | On by default. Without it, twenty multibranch branches all called `main` are indistinguishable. |
| **Show the build number** | Off by default. At four metres you cannot read it, and it costs space the job name needs. |

### Strip from job names

A regular expression whose matches are removed from every label.

Job names in a real Jenkins share long prefixes and suffixes that carry no information on a wall.
Stripping them leaves more room for the part that actually differs, which means the text can be
drawn larger:

| Pattern | `ci-decision-control-pipeline` becomes |
| --- | --- |
| `^ci-` | `decision-control-pipeline` |
| `-pipeline$` | `ci-decision-control` |
| `^ci-\|-pipeline$` | `decision-control` |

Leftover separators are tidied up afterwards. A pattern that would erase a name entirely is ignored
for that job, because a blank tile helps nobody.

## Sizing

### When jobs do not fit

| | |
| --- | --- |
| **Fit everything on one screen** | The default, and the point of the plugin. Tiles shrink until all of them fit. Nothing is ever hidden. |
| **Keep tiles readable and scroll** | Tiles hold at the minimum height below and the wall glides up and down instead. |

There is no pagination and there will not be. A job you can only see half the time is a job nobody
sees. If a wall is too dense to read, the honest fix is a narrower view or **Show → Only problems**,
not a page you have to wait for.

### Minimum tile height

Only used in scroll mode. The height below which tiles stop shrinking.

### Refresh every

Seconds between polls. Default 6.

Each open wall costs the controller roughly one request per interval. A handful of screens at five
seconds is nothing; twenty televisions at two seconds is not. Progress bars are interpolated in the
browser between polls, so they stay smooth at longer intervals — you do not need a fast refresh to
get smooth animation.

A wall in a hidden browser tab automatically backs off to a quarter of the rate.

## URL parameters

The kiosk page accepts overrides on the URL, which is the easy way to tune a screen you cannot
comfortably type on. They do not change the saved configuration.

```
…/view/<name>/wall?palette=neon&shape=octagon&refresh=10&header=0
```

| Parameter | Values |
| --- | --- |
| `palette` | `vivid` `neon` `contrast` `daylight` `colorsafe` `midnight` |
| `shape` | `rectangle` `rounded` `square` `circle` `octagon` `hexagon` `diamond` `parallelogram` `chevron` `cross` |
| `animation` | `progress` `sweep` `stripes` `pulse` `none` |
| `packing` | `interlock` `grid` |
| `sizing` | `fit` `scroll` |
| `sort` | `running` `status` `success` `name` `recent` `view-order` |
| `include` | Name patterns, as in the filter above |
| `exclude` | Name patterns to drop, e.g. `dev-*` |
| `gap` | Pixels between tiles |
| `seam` | Pixels of separator inside each tile |
| `refresh` | Seconds |
| `header` | `0` or `1` |
| `burnin` | `0` or `1` |

The view page has a preview bar that does the same thing against your real jobs: drop-downs for
palette, shape, animation, packing and order, and sliders for the gap and the separator. Moving one
changes the wall in front of you and nothing else, so you can try a palette on the real thing
without committing to it.

**Save this look** then keeps what you arrived at. It writes those seven settings to the view, so
the kiosk page and full screen open with them, for everyone — the point being that the look you
chose by eye is the look the television gets, without reproducing the same choices from memory
under **Configure**.

Only those seven are written. Anything else the view carries — sizing, status scope, the refresh
interval, the name filters — is left exactly as it was, so saving a look cannot quietly undo
something set on the Configure page. The button appears only if you have permission to configure
the view, and the endpoint behind it checks that permission again rather than trusting the button's
absence.

Ordering is decided on the server, so `?sort=` travels to the data endpoint rather than being
restyled in the browser. `…/view/<name>/wallData?sortBy=status` is a perfectly good API call on its
own.

## Configuration as code

Every setting has a `@DataBoundSetter`, so a wall can be defined in JCasC or created by script. The
view type's symbol is `liveWall`.

Scripted creation only needs the fields you care about — everything else falls back to its default:

```bash
curl -X POST "$JENKINS/createView?name=Live%20Wall" \
  -H "$CRUMB" -H 'Content-Type: application/xml' --data-binary @- <<'XML'
<io.jenkins.plugins.livewall.LiveWallView>
  <name>Live Wall</name>
  <includeRegex>.*</includeRegex>
  <palette>COLORSAFE</palette>
  <shape>HEXAGON</shape>
  <packing>INTERLOCK</packing>
  <sortBy>RUNNING</sortBy>
  <tileGap>0</tileGap>
  <statusScope>PROBLEMS</statusScope>
</io.jenkins.plugins.livewall.LiveWallView>
XML
```

Enum values are the constant names — `VIVID`, `HEXAGON`, `RUNNING`, `PROBLEMS` and so on.

## The data endpoint

The wall polls `…/view/<name>/wallData`, which is a normal Jenkins endpoint you can use for
anything else:

```json
{
  "generatedAt": 1757168400000,
  "tiles": [
    { "label": "decision-control", "name": "ci-decision-control-pipeline",
      "url": "job/ci-decision-control-pipeline/", "status": "failure", "buildNumber": 118 },
    { "label": "horizon", "name": "horizon", "url": "job/horizon/", "status": "success",
      "building": true, "startedAt": 1757168380000, "estimatedDuration": 240000 }
  ]
}
```

`status` is one of `success` `failure` `unstable` `aborted` `notbuilt` `disabled`. `building`,
`queued` and `buildNumber` are omitted when they are false or zero. It respects permissions: jobs
you cannot read are not in it.
