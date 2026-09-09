# Building, testing and installing

## What you need

- **JDK 21 or newer**, to build and to run. This is inherited from the baseline rather than chosen
  here: Jenkins core 2.555.3 is itself compiled to Java 21 bytecode, so every controller running
  that version is already on Java 21. Lowering `maven.compiler.release` would therefore widen
  nothing — there is no Java 17 controller able to run this baseline in the first place. CI builds
  on 21 and 25.
- **Maven 3.9** or newer.

Check both:

```bash
java -version
mvn -version
```

## Build

```bash
mvn clean package
```

The plugin lands at **`target/live-wall.hpi`**.

> **If your `~/.m2/settings.xml` mirrors `external:*`**
>
> A private repository manager that does not proxy `repo.jenkins-ci.org` will break resolution of
> the Jenkins parent pom. This repository ships a minimal settings file for exactly that case:
>
> ```bash
> mvn -s .mvn/settings.xml clean package
> ```
>
> Add `-s .mvn/settings.xml` to every command on this page if that applies to you. Otherwise ignore
> it — plain `mvn` is fine, and that is what CI uses.

## Run the tests

```bash
mvn clean verify
```

That runs everything the CI gate runs:

| | |
| --- | --- |
| `LiveWallViewTest` | The view itself, on a real Jenkins started by the test harness: tile contents, sorting, scoping, name rewriting, colour validation, the JSON endpoint, a full configuration round trip through the form, and both pages rendering |
| `InjectedTest` | Supplied by the Jenkins plugin parent: validates every Jelly file, checks localisation and the plugin metadata |
| SpotBugs, spotless | Static analysis and formatting, also from the parent |

To run one test while you are working on it:

```bash
mvn test -Dtest='LiveWallViewTest#sortByStatusFloatsProblemsToTheTopLeft'
```

Formatting failures are fixed for you:

```bash
mvn spotless:apply
```

### If the tests hang on your machine

The Jenkins test harness shuts down its agent listener by connecting back to the machine's own
hostname. On a laptop whose hostname resolves to a LAN or VPN address it cannot reach — common on
macOS — that connect has no timeout and hangs forever.

`pom.xml` already disables the agent listener during tests for this reason, so you should not hit
it. If you ever do, this is the knob:

```bash
mvn clean verify -Djenkins.model.Jenkins.slaveAgentPort=-1
```

## Run a throwaway Jenkins with the plugin loaded

```bash
mvn hpi:run
```

Then open **http://localhost:8080/jenkins**. This is the fastest loop for working on the wall: it
starts a fresh controller with the plugin already installed, and its data lives in `work/`, which is
git-ignored, so you can delete it whenever you like.

Use a different port with `-Djetty.port=9090`.

Create a few jobs, make some of them fail, then **New View → Live Wall** and you have something to
look at. A job that sleeps is handy for watching the in-progress animation:

```groovy
// a pipeline job that gives you thirty seconds of animation to look at
pipeline {
    agent any
    stages {
        stage('Slow') { steps { sleep 30 } }
    }
}
```

Run it twice: the first build teaches Jenkins the estimated duration, and the second one shows the
progress fill tracking it properly.

## Install a build of your own into your own Jenkins

Normally you would install Live Wall from **Manage Jenkins → Plugins → Available plugins**, and that
is what the README tells people to do. This page is for installing a build *you* made — a change you
want to try on a real controller before proposing it.

Releases carry no `.hpi` attachment to download: this plugin is released by
[continuous delivery](releasing.md), which publishes to the Jenkins Maven repository, and the update
centre serves it from there. So build it:

1. `mvn clean package`, which writes `target/live-wall.hpi`.
2. In Jenkins: **Manage Jenkins → Plugins → Advanced settings**.
3. Under **Deploy Plugin**, choose that `.hpi` file and press **Deploy**.
4. Restart Jenkins when it offers to.

Requires **Jenkins 2.555.3 or newer**, running on **Java 21 or newer**.

To upgrade, deploy the newer `.hpi` the same way; the view configuration is preserved.

## Working on the front end

The wall's CSS and JavaScript are Jenkins *adjuncts*, which live next to the Java rather than in
`src/main/webapp`:

| File | What it is |
| --- | --- |
| `src/main/resources/io/jenkins/plugins/livewall/wall.css` | Palettes, shapes, animations |
| `src/main/resources/io/jenkins/plugins/livewall/wall.js` | Layout, text fitting, contrast, polling |
| `src/main/resources/lib/livewall/wall.jelly` | The markup shell, shared by both pages |

Adjuncts are cached hard by the browser and keyed on a token that changes when Jenkins restarts, so
during `hpi:run` a hard reload (or DevTools with caching disabled) is worth having on.

Two things to know before editing them:

- `SHAPE_INSET` in `wall.js` and `--lw-text-inset` in `wall.css` describe the same thing — how much
  of a tile each silhouette eats. Change one and you must change the other, or labels start
  overflowing their shapes.
- Job names are user input. Build labels with `textContent` and `document.createElement`, never with
  `innerHTML`.
