/*
 * Live Wall - the browser half of the plugin.
 *
 * The server sends a small JSON document; everything else happens here, because a wall that is
 * going to sit on a television for six months must not reload the page and must not care that the
 * screen is 1366x768 in one office and 3840x2160 in another.
 *
 * The three things worth reading:
 *
 *   fitColumns()   picks the column count that makes every tile as large as possible while still
 *                  fitting all of them on screen. This is what replaces pagination.
 *   fitFontSize()  measures the real glyphs on a canvas and binary-searches the largest font size
 *                  at which a job name still fits its tile, instead of guessing and clipping.
 *   inkFor()       picks black or white text per status from the actual contrast ratio, so a
 *                  hand-picked custom palette stays readable without anyone thinking about it.
 */
(function () {
    "use strict";

    var STATUSES = ["success", "failure", "unstable", "aborted", "notbuilt", "disabled"];

    /* Custom colour overrides, and the CSS custom properties each one drives. */
    var COLOR_TARGETS = {
        background: ["--lw-bg"],
        success: ["--lw-success"],
        failure: ["--lw-failure"],
        unstable: ["--lw-unstable"],
        aborted: ["--lw-aborted"],
        idle: ["--lw-notbuilt", "--lw-disabled"],
    };

    /* Fraction of a tile that the silhouette takes away from the label on each side.
       Must stay in step with --lw-text-inset in live-wall.css. */
    var SHAPE_INSET = {
        rectangle: 0.07,
        rounded: 0.08,
        square: 0.07,
        circle: 0.18,
        octagon: 0.13,
        hexagon: 0.16,
        diamond: 0.26,
        parallelogram: 0.14,
        chevron: 0.14,
        cross: 0.3,
    };

    /* Tile proportions each shape looks best at. Only an input to the column search: tiles still
       stretch to fill their grid cell, except for the shapes forced square below. */
    var SHAPE_ASPECT = {
        rectangle: 2.2,
        rounded: 2.2,
        square: 1,
        circle: 1,
        octagon: 1.3,
        hexagon: 1.4,
        // A diamond spans two rows, so a square-ish diamond wants a cell twice as wide as it is
        // tall. Asking for a square cell here is what makes them come out long and thin.
        diamond: 2,
        parallelogram: 2.2,
        chevron: 2,
        cross: 1,
    };

    var SQUARE_SHAPES = { square: 1, circle: 1, cross: 1 };

    /*
     * How each shape locks into its neighbours, in fractions of a grid cell.
     *
     *   bleedX/bleedY  how far the silhouette overhangs its cell, so that adjacent shapes meet
     *                  along a shared edge instead of merely sitting next to each other.
     *   shift          which alternate line slides by half a cell: "column" moves every other
     *                  column down, "row" moves every other row across.
     *
     * The numbers are geometry, not taste, and they have to match the clip-paths in wall.css:
     *
     *   hexagon        vertices at 25%/75% put the horizontal step at 0.75 of the width, so the
     *                  shape is 1/0.75 of a cell wide and alternate columns drop half a row. A
     *                  honeycomb.
     *   diamond        rows step by half the shape height and alternate rows shift half a cell
     *                  across, which is a square lattice stood on its corner.
     *   parallelogram  the 11% slant means the step is 0.89 of the width.
     *   chevron        the point reaches 100% and the notch starts at 12%, so the step is 0.88.
     *
     * Shapes absent from this table already tile edge to edge at zero gap (rectangle, rounded,
     * square, octagon, cross) or cannot tile at all (circle).
     */
    var TESSELLATION = {
        hexagon: { bleedX: 1 / 3, bleedY: 0, shift: "column", shiftX: 0, shiftY: 0.5 },
        diamond: { bleedX: 0, bleedY: 1, shift: "row", shiftX: 0.5, shiftY: 0 },
        parallelogram: { bleedX: 1 / 0.89 - 1, bleedY: 0, shift: "none", shiftX: 0, shiftY: 0 },
        chevron: { bleedX: 1 / 0.88 - 1, bleedY: 0, shift: "none", shiftX: 0, shiftY: 0 },
    };

    /* Overrides accepted on the URL, so a wall can be retuned from the TV's address bar without
       touching the saved view configuration. */
    var URL_OVERRIDES = {
        palette: "palette",
        shape: "shape",
        animation: "animation",
        sizing: "sizing",
        packing: "packing",
        sort: "sortBy",
        sortby: "sortBy",
        include: "include",
        exclude: "exclude",
        header: "header",
        burnin: "burnIn",
    };

    /** Map lookup that cannot be steered off the prototype chain by a crafted URL parameter. */
    function lookup(map, key, fallback) {
        return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback;
    }

    /* Canvas gives exact glyph widths, but it is not guaranteed: some kiosk browsers and privacy
       settings disable it outright. Everything that uses it degrades to an estimate instead. */
    var measureContext = (function () {
        try {
            return document.createElement("canvas").getContext("2d");
        } catch (error) {
            return null;
        }
    })();
    var glyphWidths = Object.create(null);

    /* Average glyph width as a fraction of the font size, used when canvas is unavailable. */
    var ESTIMATED_GLYPH_RATIO = 0.55;

    function boot() {
        var roots = document.querySelectorAll(".lw-root");
        for (var i = 0; i < roots.length; i++) {
            new Wall(roots[i]).start();
        }
    }

    // Adjuncts can land either side of DOMContentLoaded depending on where Jenkins hoists them.
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }

    function Wall(root) {
        this.root = root;
        this.grid = root.querySelector(".lw-grid");
        this.scroller = root.querySelector(".lw-scroller");
        this.stage = root.querySelector(".lw-stage");
        this.banner = root.querySelector(".lw-banner");
        this.emptyMessage = root.querySelector(".lw-message");
        this.clock = root.querySelector(".lw-clock");

        this.counts = {};
        var self = this;
        ["failure", "unstable", "building", "success"].forEach(function (key) {
            self.counts[key] = root.querySelector('.lw-count[data-status="' + key + '"]');
        });

        this.dataUrl = root.dataset.dataUrl;
        this.rootUrl = root.dataset.rootUrl || "";
        this.refreshMs = Math.max(2000, (parseInt(root.dataset.refresh, 10) || 6) * 1000);
        this.minTileHeight = parseInt(root.dataset.minTileHeight, 10) || 110;
        this.tileGap = Math.max(0, parseInt(root.dataset.gap, 10) || 0);
        this.seamWidth = Math.max(0, parseInt(root.dataset.seam, 10) || 0);
        this.showBuildNumber = root.dataset.showBuildNumber === "true";

        this.tiles = new Map(); // full job name -> element
        this.building = []; // entries with a build in progress, for the progress ticker
        this.order = "";
        this.failures = 0;
        this.clockSkew = 0; // server time minus browser time, so a wrong TV clock cannot lie
        this.scrollAnimation = null;
        this.pendingLayout = 0;
        this.layoutRetries = 0;
        this.timer = 0;
    }

    Wall.prototype.start = function () {
        this.applyUrlOverrides();
        this.applySpacing();
        this.applyCustomColors();
        this.refreshInk();
        this.bindControls();

        var self = this;
        if (window.ResizeObserver) {
            new ResizeObserver(function () {
                self.scheduleLayout();
            }).observe(this.scroller);
        } else {
            window.addEventListener("resize", function () {
                self.scheduleLayout();
            });
        }
        document.addEventListener("fullscreenchange", function () {
            self.scheduleLayout();
        });
        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                self.poll();
            }
        });

        this.settleLayout();
        this.tickClock();
        window.setInterval(this.tickClock.bind(this), 10000);
        window.setInterval(this.tickProgress.bind(this), 500);
        this.poll();
    };

    /* ------------------------------------------------------------------ configuration */

    Wall.prototype.applyUrlOverrides = function () {
        if (!window.URLSearchParams) {
            return;
        }
        var params = new URLSearchParams(window.location.search);
        var root = this.root;
        Object.keys(URL_OVERRIDES).forEach(function (param) {
            var value = params.get(param);
            if (value === null) {
                return;
            }
            var key = URL_OVERRIDES[param];
            var enabled = value === "1" || value === "true";
            if (key === "header") {
                root.dataset.showHeader = enabled ? "true" : "false";
            } else if (key === "burnIn") {
                root.dataset.burnIn = enabled ? "true" : "false";
            } else {
                root.dataset[key] = value;
            }
        });

        var refresh = parseInt(params.get("refresh"), 10);
        if (refresh > 0) {
            this.refreshMs = Math.max(2000, refresh * 1000);
        }
        var gap = parseInt(params.get("gap"), 10);
        if (gap >= 0) {
            this.tileGap = Math.min(64, gap);
        }
        var seam = parseInt(params.get("seam"), 10);
        if (seam >= 0) {
            this.seamWidth = Math.min(12, seam);
        }
        var header = this.root.querySelector(".lw-header");
        if (header) {
            header.dataset.visible = this.root.dataset.showHeader === "false" ? "false" : "true";
        }
    };

    /* Gap and seam are two different things: the gap is empty background between tiles, the seam is
       a hairline of background drawn inside each tile's own outline. At the default gap of zero the
       seam is the only thing keeping a run of same-coloured neighbours countable. */
    Wall.prototype.applySpacing = function () {
        this.root.style.setProperty("--lw-gap", this.tileGap + "px");
        this.root.style.setProperty("--lw-seam", this.seamWidth + "px");
    };

    /* Custom colours override whichever palette is selected, so you can start from a built-in one
       and change only the colour that bothers you. */
    Wall.prototype.applyCustomColors = function () {
        var root = this.root;
        Object.keys(COLOR_TARGETS).forEach(function (key) {
            var value = root.dataset["color" + key.charAt(0).toUpperCase() + key.slice(1)];
            if (!value) {
                return;
            }
            COLOR_TARGETS[key].forEach(function (property) {
                root.style.setProperty(property, value);
            });
        });
    };

    /* Picks the text colour for each status from its measured contrast against the fill. */
    Wall.prototype.refreshInk = function () {
        var computed = window.getComputedStyle(this.root);
        for (var i = 0; i < STATUSES.length; i++) {
            var status = STATUSES[i];
            var fill = computed.getPropertyValue("--lw-" + status).trim();
            if (!fill) {
                continue; // stylesheet not applied yet; the CSS fallback ink is fine
            }
            this.root.style.setProperty("--lw-ink-" + status, inkFor(fill));
        }
    };

    Wall.prototype.bindControls = function () {
        var self = this;

        var fullscreen = document.querySelector('[data-lw-action="fullscreen"]');
        if (fullscreen) {
            fullscreen.addEventListener("click", function (event) {
                event.preventDefault();
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                } else if (self.root.requestFullscreen) {
                    self.root.requestFullscreen();
                }
            });
        }

        var save = document.querySelector('[data-lw-action="save"]');
        if (save) {
            save.addEventListener("click", function (event) {
                event.preventDefault();
                self.saveLook(save);
            });
        }

        var controls = document.querySelectorAll("[data-lw-preview]");
        for (var i = 0; i < controls.length; i++) {
            var control = controls[i];
            var key = control.getAttribute("data-lw-preview");
            var current = key === "gap" ? this.tileGap : key === "seam" ? this.seamWidth : this.root.dataset[key];
            if (current !== undefined && current !== null && current !== "") {
                control.value = current;
            }
            this.showPreviewValue(control, key);

            // Sliders report on "input" so the wall moves while you drag it.
            ["change", "input"].forEach(function (eventName) {
                control.addEventListener(eventName, function (event) {
                    self.applyPreview(event.target.getAttribute("data-lw-preview"), event.target.value);
                    self.showPreviewValue(event.target, event.target.getAttribute("data-lw-preview"));
                });
            });
        }
    };

    /** Applies one preview control. Nothing here is saved; Configure is what persists a look. */
    Wall.prototype.applyPreview = function (key, value) {
        if (key === "gap") {
            this.tileGap = Math.max(0, Math.min(64, parseInt(value, 10) || 0));
            this.applySpacing();
        } else if (key === "seam") {
            this.seamWidth = Math.max(0, Math.min(12, parseInt(value, 10) || 0));
            this.applySpacing();
        } else if (key === "sortBy") {
            // Ordering is the server's decision, so ask it again rather than reshuffling here.
            this.root.dataset.sortBy = value;
            this.poll();
        } else {
            this.root.dataset[key] = value;
            this.refreshInk();
        }
        this.scheduleLayout();
    };

    /**
     * Writes what the preview bar is currently showing to the view, so the kiosk page and full
     * screen open with it. Sends only the settings the bar owns; the server leaves the rest alone.
     */
    Wall.prototype.saveLook = function (button) {
        var self = this;
        var url = button.dataset.lwSaveUrl;
        if (!url) {
            return;
        }

        var body = new URLSearchParams();
        body.set("palette", this.root.dataset.palette || "");
        body.set("shape", this.root.dataset.shape || "");
        body.set("animation", this.root.dataset.animation || "");
        body.set("packing", this.root.dataset.packing || "");
        body.set("sortBy", this.root.dataset.sortBy || "");
        body.set("gap", String(this.tileGap));
        body.set("seam", String(this.seamWidth));

        // The crumb is rendered into the button server-side: it is bound to the HTTP session, so it
        // cannot be fetched from a different one and has to travel with the request.
        var headers = { Accept: "application/json" };
        if (button.dataset.lwCrumbField && button.dataset.lwCrumb) {
            headers[button.dataset.lwCrumbField] = button.dataset.lwCrumb;
        }

        button.disabled = true;
        fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: headers,
            body: body,
        })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                self.announce(button.dataset.lwSavedText || "Saved.");
            })
            .catch(function () {
                self.announce(button.dataset.lwFailedText || "Could not save.");
            })
            .then(function () {
                button.disabled = false;
            });
    };

    /** Replaces the hint under the preview bar with a one-off message. */
    Wall.prototype.announce = function (message) {
        var hint = document.querySelector('[data-lw-role="hint"]');
        if (hint) {
            hint.textContent = message;
        }
    };

    /** Keeps the number next to a slider in step with it. */
    Wall.prototype.showPreviewValue = function (control, key) {
        var output = document.querySelector('[data-lw-output="' + key + '"]');
        if (output) {
            output.textContent = control.value;
        }
    };

    /* ------------------------------------------------------------------ polling */

    Wall.prototype.poll = function () {
        var self = this;
        window.clearTimeout(this.timer);

        if (document.hidden) {
            // Nobody is looking; do not spend controller time on this tab.
            this.timer = window.setTimeout(this.poll.bind(this), this.refreshMs * 4);
            return;
        }

        fetch(this.currentDataUrl(), {
            credentials: "same-origin",
            cache: "no-store",
            headers: { Accept: "application/json" },
        })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            })
            .then(function (data) {
                self.failures = 0;
                self.banner.classList.remove("lw-on");
                if (typeof data.generatedAt === "number") {
                    self.clockSkew = data.generatedAt - Date.now();
                }
                self.render(data.tiles || []);
            })
            .catch(function (error) {
                self.failures++;
                // One dropped poll on a wifi TV is not news; three in a row is.
                if (self.failures >= 3) {
                    self.banner.textContent = "Lost contact with Jenkins - " + error.message;
                    self.banner.classList.add("lw-on");
                }
            })
            .then(function () {
                self.timer = window.setTimeout(self.poll.bind(self), self.refreshMs);
            });
    };

    /* Ordering happens on the server, so an override rides along with the request. */
    Wall.prototype.currentDataUrl = function () {
        var url = this.dataUrl;
        var root = this.root;
        var separator = url.indexOf("?") >= 0 ? "&" : "?";

        ["sortBy", "include", "exclude"].forEach(function (key) {
            var value = root.dataset[key];
            if (value === undefined || value === null || value === "") {
                return;
            }
            url += separator + key + "=" + encodeURIComponent(value);
            separator = "&";
        });
        return url;
    };

    /* ------------------------------------------------------------------ rendering */

    Wall.prototype.render = function (list) {
        var self = this;
        var structureChanged = false;
        var seen = new Set();

        list.forEach(function (tile) {
            seen.add(tile.name);
            var element = self.tiles.get(tile.name);
            if (!element) {
                element = self.createTile(tile);
                self.tiles.set(tile.name, element);
                self.grid.appendChild(element);
                structureChanged = true;
            }
            if (self.updateTile(element, tile)) {
                structureChanged = true;
            }
        });

        this.tiles.forEach(function (element, name) {
            if (!seen.has(name)) {
                element.remove();
                self.tiles.delete(name);
                structureChanged = true;
            }
        });

        var order = list
            .map(function (tile) {
                return tile.name;
            })
            .join("\n");
        if (order !== this.order) {
            list.forEach(function (tile) {
                self.grid.appendChild(self.tiles.get(tile.name));
            });
            this.order = order;
            structureChanged = true;
        }

        this.building = list
            .filter(function (tile) {
                return tile.building;
            })
            .map(function (tile) {
                return { tile: tile, element: self.tiles.get(tile.name) };
            });

        this.emptyMessage.textContent = this.root.dataset.emptyMessage || "Nothing to show.";
        this.emptyMessage.classList.toggle("lw-on", list.length === 0);
        this.updateCounts(list);
        this.tickProgress();

        if (structureChanged) {
            this.scheduleLayout();
        }
    };

    Wall.prototype.createTile = function (data) {
        var anchor = document.createElement("a");
        anchor.className = "lw-tile";
        anchor.href = this.rootUrl + data.url;
        anchor.setAttribute("role", "listitem");

        // Two nested layers carrying the same silhouette: the outer one is the seam colour, the
        // inner one is the fill, inset by the seam width. That is what makes the hairline follow
        // the outline of a hexagon rather than the outline of its bounding box.
        var shape = document.createElement("span");
        shape.className = "lw-shape";

        var face = document.createElement("span");
        face.className = "lw-face";

        var effects = document.createElement("span");
        effects.className = "lw-fx";
        effects.setAttribute("aria-hidden", "true");

        var label = document.createElement("span");
        label.className = "lw-label";

        face.appendChild(effects);
        face.appendChild(label);

        if (this.showBuildNumber) {
            var badge = document.createElement("span");
            badge.className = "lw-badge";
            badge.setAttribute("aria-hidden", "true");
            face.appendChild(badge);
        }

        shape.appendChild(face);
        anchor.appendChild(shape);
        return anchor;
    };

    /** Returns true when something changed that needs the layout recomputing. */
    Wall.prototype.updateTile = function (element, data) {
        var needsLayout = false;

        if (element.dataset.label !== data.label) {
            element.dataset.label = data.label;
            setLabel(element.querySelector(".lw-label"), data.label);
            needsLayout = true;
        }
        if (element.dataset.status !== data.status) {
            element.dataset.status = data.status;
        }
        setFlag(element, "building", data.building);
        setFlag(element, "queued", data.queued);

        var description = data.name + ": " + data.status + (data.building ? ", building" : "");
        if (element.getAttribute("aria-label") !== description) {
            element.setAttribute("aria-label", description);
            element.title = description;
        }

        if (this.showBuildNumber) {
            var badge = element.querySelector(".lw-badge");
            var text = data.buildNumber ? "#" + data.buildNumber : "";
            if (badge && badge.textContent !== text) {
                badge.textContent = text;
            }
        }
        return needsLayout;
    };

    Wall.prototype.updateCounts = function (list) {
        var totals = { failure: 0, unstable: 0, building: 0, success: 0 };
        list.forEach(function (tile) {
            if (totals[tile.status] !== undefined) {
                totals[tile.status]++;
            }
            if (tile.building) {
                totals.building++;
            }
        });

        var labels = {
            failure: "failing",
            unstable: "unstable",
            building: "building",
            success: "passing",
        };
        var self = this;
        Object.keys(totals).forEach(function (key) {
            var element = self.counts[key];
            if (!element) {
                return;
            }
            element.textContent = totals[key] + " " + labels[key];
            // A zero for something bad is worth saying out loud; a zero for the rest is noise.
            element.classList.toggle("lw-on", totals[key] > 0 || key === "failure");
        });
    };

    /* Advances the progress fills between polls, so a five second refresh still looks continuous. */
    Wall.prototype.tickProgress = function () {
        var now = Date.now() + this.clockSkew;
        for (var i = 0; i < this.building.length; i++) {
            var element = this.building[i].element;
            if (!element) {
                continue;
            }
            var data = this.building[i].tile;
            var effects = element.querySelector(".lw-fx");
            var estimate = data.estimatedDuration;
            var elapsed = now - data.startedAt;

            if (!estimate || estimate <= 0 || elapsed >= estimate) {
                // No estimate, or the build has already overrun it: stop pretending we know.
                element.dataset.indeterminate = "true";
                effects.style.width = "";
            } else {
                delete element.dataset.indeterminate;
                var fraction = Math.max(0.02, Math.min(0.99, elapsed / estimate));
                effects.style.width = (fraction * 100).toFixed(1) + "%";
            }
        }
    };

    Wall.prototype.tickClock = function () {
        if (!this.clock) {
            return;
        }
        this.clock.textContent = new Date().toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
        });
    };

    /* ------------------------------------------------------------------ layout */

    Wall.prototype.scheduleLayout = function () {
        var self = this;
        if (this.pendingLayout) {
            return;
        }
        this.pendingLayout = window.requestAnimationFrame(function () {
            self.pendingLayout = 0;
            self.layout();
        });
    };

    /** Re-runs the layout shortly, up to a point, when the page was not measurable yet. */
    Wall.prototype.retryLayout = function () {
        var self = this;
        if (this.layoutRetries >= 40) {
            return;
        }
        this.layoutRetries++;
        window.setTimeout(function () {
            self.scheduleLayout();
        }, 100);
    };

    /* Web fonts and stylesheets can land after the first layout and change every measurement, so
       take another look once things have settled. Cheap, and it happens twice in a wall's life. */
    Wall.prototype.settleLayout = function () {
        var self = this;
        [250, 1000, 3000].forEach(function (delay) {
            window.setTimeout(function () {
                self.scheduleLayout();
            }, delay);
        });
        if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
            document.fonts.ready.then(function () {
                self.scheduleLayout();
            });
        }
    };

    Wall.prototype.layout = function () {
        var count = this.tiles.size;
        if (!count) {
            return;
        }
        var width = this.scroller.clientWidth;
        var height = this.scroller.clientHeight;
        if (width < 10 || height < 10) {
            // The stage has no usable size yet -- a font still loading, a stylesheet still
            // arriving, a tab opened in the background. Giving up here would leave the wall
            // permanently unlaid-out, with tiles at their default size on top of each other, so
            // come back and try again rather than waiting for a resize that may never happen.
            this.retryLayout();
            return;
        }

        var shape = this.root.dataset.shape || "rounded";
        var aspect = lookup(SHAPE_ASPECT, shape, 2);
        var gap = this.tileGap;

        // A tessellating shape overhangs its cell, and the shifted line runs past the last row or
        // column. Both have to come out of the space available before columns are chosen, or the
        // edges of the wall get clipped.
        var tess = this.root.dataset.packing === "grid" ? null : lookup(TESSELLATION, shape, null);
        var extraX = tess ? tess.bleedX + (tess.shift === "row" ? tess.shiftX : 0) : 0;
        var extraY = tess ? tess.bleedY + (tess.shift === "column" ? tess.shiftY : 0) : 0;

        var columns;
        var rows;
        var cellWidth;
        var cellHeight;

        if (this.root.dataset.sizing === "scroll") {
            columns = Math.max(1, Math.min(count, Math.round(width / (this.minTileHeight * aspect))));
            rows = Math.ceil(count / columns);
            cellWidth = (width - gap * (columns - 1)) / (columns + extraX);
            cellHeight = Math.max(this.minTileHeight, (height - gap * (rows - 1)) / (rows + extraY));
        } else {
            columns = fitColumns(count, width, height, gap, aspect, extraX, extraY);
            rows = Math.ceil(count / columns);
            cellWidth = (width - gap * (columns - 1)) / (columns + extraX);
            cellHeight = (height - gap * (rows - 1)) / (rows + extraY);
        }
        if (!(cellWidth > 0) || !(cellHeight > 0)) {
            return;
        }

        // Explicit pixel tracks rather than 1fr, so the grid can be smaller than its container and
        // centred, leaving room for the overhang instead of running off the edge.
        this.root.style.setProperty("--lw-cols", columns);
        this.grid.style.gridTemplateColumns = "repeat(" + columns + ", " + cellWidth + "px)";
        this.grid.style.gridAutoRows = cellHeight + "px";
        this.grid.style.width = columns * cellWidth + gap * (columns - 1) + "px";
        this.grid.style.height = rows * cellHeight + gap * (rows - 1) + "px";

        // Centre the whole footprint, overhang included. The bleed sticks out symmetrically, but a
        // shifted line only ever runs past one edge, so "margin: auto" would centre the grid box and
        // let the shifted half of the wall fall off the bottom.
        var overhangTop = (tess ? tess.bleedY : 0) * cellHeight / 2;
        var overhangLeft = (tess ? tess.bleedX : 0) * cellWidth / 2;
        var overhangBottom =
            overhangTop + (tess && tess.shift === "column" ? tess.shiftY * (cellHeight + gap) : 0);
        var overhangRight =
            overhangLeft + (tess && tess.shift === "row" ? tess.shiftX * (cellWidth + gap) : 0);

        var gridWidth = columns * cellWidth + gap * (columns - 1);
        var gridHeight = rows * cellHeight + gap * (rows - 1);
        var left = (width - (gridWidth + overhangLeft + overhangRight)) / 2 + overhangLeft;
        var top =
            this.root.dataset.sizing === "scroll"
                ? overhangTop
                : (height - (gridHeight + overhangTop + overhangBottom)) / 2 + overhangTop;

        this.grid.style.marginLeft = Math.max(0, left) + "px";
        this.grid.style.marginTop = Math.max(0, top) + "px";

        // A tessellating shape is bigger than its cell by exactly the overhang its geometry needs;
        // everything else is simply the cell.
        var shapeWidth = tess ? cellWidth * (1 + tess.bleedX) : cellWidth;
        var shapeHeight = tess ? cellHeight * (1 + tess.bleedY) : cellHeight;
        if (lookup(SQUARE_SHAPES, shape, false)) {
            shapeWidth = Math.min(cellWidth, cellHeight);
            shapeHeight = shapeWidth;
        }
        this.root.style.setProperty("--lw-shape-w", shapeWidth + "px");
        this.root.style.setProperty("--lw-shape-h", shapeHeight + "px");
        this.root.style.setProperty(
            "--lw-shift-x",
            (tess && tess.shift === "row" ? tess.shiftX * (cellWidth + gap) : 0) + "px"
        );
        this.root.style.setProperty(
            "--lw-shift-y",
            (tess && tess.shift === "column" ? tess.shiftY * (cellHeight + gap) : 0) + "px"
        );

        this.layoutRetries = 0;
        this.placeTiles(count, columns, tess);
        this.resizeText(shapeWidth, shapeHeight, shape);
        this.updateScrolling();
    };

    /*
     * Places every tile and marks the ones that slide half a cell.
     *
     * These two jobs have to happen together. A trailing part-row looks wrong jammed against the
     * left edge, so it gets centred -- but centring moves those tiles to columns that no longer
     * match their position in the DOM, and the interlock offset is decided by column parity. Work
     * them out separately and the last row shifts the wrong way, landing half a row on top of the
     * row above it.
     *
     * So a part-row is only centred when nothing is being shifted. Sliding it sideways would break
     * the tessellation it is supposed to slot into anyway.
     */
    Wall.prototype.placeTiles = function (count, columns, tess) {
        var children = this.grid.children;
        var shiftsColumns = tess && tess.shift === "column";
        var shiftsRows = tess && tess.shift === "row";
        var interlocking = shiftsColumns || shiftsRows;

        var remainder = columns > 1 ? count % columns : 0;
        var firstOfLastRow = count - remainder;
        var offset = remainder && !interlocking ? Math.floor((columns - remainder) / 2) : 0;

        for (var i = 0; i < children.length; i++) {
            var element = children[i];
            var inLastRow = remainder !== 0 && i >= firstOfLastRow;
            var column = inLastRow ? offset + (i - firstOfLastRow) : i % columns;

            element.style.gridColumnStart = inLastRow && i === firstOfLastRow && offset > 0 ? String(offset + 1) : "";

            var line = shiftsColumns ? column : Math.floor(i / columns);
            if (interlocking && line % 2 === 1) {
                element.dataset.shift = "true";
            } else {
                delete element.dataset.shift;
            }
        }
    };

    Wall.prototype.resizeText = function (shapeWidth, shapeHeight, shape) {
        var inset = lookup(SHAPE_INSET, shape, 0.1);
        var seam = this.seamWidth;
        var availableWidth = (shapeWidth - seam * 2) * (1 - inset * 2);
        var availableHeight = (shapeHeight - seam * 2) * (1 - inset * 2);

        var font = "800 100px " + window.getComputedStyle(this.root).fontFamily;
        var cache = new Map();
        var children = this.grid.children;

        for (var i = 0; i < children.length; i++) {
            var element = children[i];
            var label = element.dataset.label || "";
            var size = cache.get(label);
            if (size === undefined) {
                size = fitFontSize(label, availableWidth, availableHeight, font);
                cache.set(label, size);
            }
            element.style.setProperty("--lw-font-size", size + "px");
        }
    };

    /* Scroll mode: glide the whole grid up and back rather than paginating it. */
    Wall.prototype.updateScrolling = function () {
        if (this.scrollAnimation) {
            this.scrollAnimation.cancel();
            this.scrollAnimation = null;
        }
        this.scroller.style.overflowY = "";
        if (this.root.dataset.sizing !== "scroll" || !this.grid.animate) {
            return;
        }
        if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            this.scroller.style.overflowY = "auto";
            return;
        }
        var overflow = this.grid.scrollHeight - this.scroller.clientHeight;
        if (overflow <= 4) {
            return;
        }
        var pixelsPerSecond = 24;
        this.scrollAnimation = this.grid.animate(
            [{ transform: "translateY(0)" }, { transform: "translateY(" + -overflow + "px)" }],
            {
                duration: Math.max(8000, (overflow / pixelsPerSecond) * 1000),
                iterations: Infinity,
                direction: "alternate",
                easing: "ease-in-out",
            }
        );
    };

    /* ------------------------------------------------------------------ pure helpers */

    /**
     * Chooses the column count that makes tiles as large as possible while keeping all of them on
     * screen. Every candidate is scored by the area of the largest tile that respects the shape's
     * preferred proportions, which is why a wall of circles lays out differently from a wall of
     * wide rectangles at the same job count.
     */
    function fitColumns(count, width, height, gap, aspect, extraX, extraY) {
        var best = 0;
        var bestScore = -1;
        for (var columns = 1; columns <= count; columns++) {
            var rows = Math.ceil(count / columns);
            var tileWidth = (width - gap * (columns - 1)) / (columns + (extraX || 0));
            var tileHeight = (height - gap * (rows - 1)) / (rows + (extraY || 0));
            if (tileWidth < 8 || tileHeight < 8) {
                continue;
            }
            var effectiveWidth = tileWidth;
            var effectiveHeight = tileHeight;
            if (effectiveWidth / effectiveHeight > aspect) {
                effectiveWidth = effectiveHeight * aspect;
            } else {
                effectiveHeight = effectiveWidth / aspect;
            }
            var score = effectiveWidth * effectiveHeight;
            if (score > bestScore) {
                bestScore = score;
                best = columns;
            }
        }
        // Nothing fit at all (a very small window): fall back to something square-ish and let the
        // tiles be tiny rather than dropping jobs off the wall.
        return best || Math.max(1, Math.ceil(Math.sqrt((count * width) / Math.max(1, height))));
    }

    /**
     * Largest font size at which the label still fits, found by binary search over real glyph
     * measurements. Job names break at spaces, hyphens, underscores and slashes, which is where
     * Jenkins job names actually want to break.
     */
    function fitFontSize(label, width, height, font) {
        if (!label || width <= 0 || height <= 0) {
            return 12;
        }
        var pieces = splitLabel(label).map(function (piece) {
            return measure(piece, font);
        });
        var lineHeight = 1.08;
        var low = 6;
        var high = Math.max(6, Math.floor(height));

        function fits(size) {
            var scale = size / 100;
            var lines = 1;
            var used = 0;
            for (var i = 0; i < pieces.length; i++) {
                var pieceWidth = pieces[i] * scale;
                if (pieceWidth > width) {
                    return false; // an unbreakable run is wider than the tile
                }
                if (used > 0 && used + pieceWidth > width) {
                    lines++;
                    used = pieceWidth;
                } else {
                    used += pieceWidth;
                }
            }
            return lines * size * lineHeight <= height;
        }

        if (!fits(low)) {
            return low; // nothing fits; the overflow is hidden and the tile still shows its colour
        }
        while (low < high) {
            var middle = Math.ceil((low + high) / 2);
            if (fits(middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Splits a job name into the chunks a line break may fall between, separators included. */
    function splitLabel(label) {
        var pieces = [];
        var current = "";
        for (var i = 0; i < label.length; i++) {
            var character = label.charAt(i);
            current += character;
            if (isSeparator(character) && !isSeparator(label.charAt(i + 1))) {
                pieces.push(current);
                current = "";
            }
        }
        if (current) {
            pieces.push(current);
        }
        return pieces.length ? pieces : [label];
    }

    function isSeparator(character) {
        return character === " " || character === "-" || character === "_" || character === "/";
    }

    /** Width of a string at 100px in the given font, cached because labels repeat across polls. */
    function measure(text, font) {
        var key = font + "\n" + text;
        var cached = glyphWidths[key];
        if (cached === undefined) {
            cached = text.length * 100 * ESTIMATED_GLYPH_RATIO;
            if (measureContext) {
                try {
                    measureContext.font = font;
                    var measured = measureContext.measureText(text).width;
                    if (measured > 0) {
                        cached = measured;
                    }
                } catch (error) {
                    // Keep the estimate.
                }
            }
            glyphWidths[key] = cached;
        }
        return cached;
    }

    /** Writes a label into an element with break opportunities, never via innerHTML. */
    function setLabel(element, label) {
        element.textContent = "";
        var pieces = splitLabel(label);
        for (var i = 0; i < pieces.length; i++) {
            element.appendChild(document.createTextNode(pieces[i]));
            if (i < pieces.length - 1) {
                element.appendChild(document.createElement("wbr"));
            }
        }
    }

    function setFlag(element, name, value) {
        if (value) {
            element.dataset[name] = "true";
        } else {
            delete element.dataset[name];
        }
    }

    /** Black or white, whichever has the better contrast ratio against the fill. */
    function inkFor(color) {
        var rgb = toRgb(color);
        if (!rgb) {
            return "#ffffff";
        }
        var luminance = relativeLuminance(rgb);
        var againstBlack = (luminance + 0.05) / 0.05;
        var againstWhite = 1.05 / (luminance + 0.05);
        return againstBlack >= againstWhite ? "#07090c" : "#ffffff";
    }

    /**
     * Resolves a CSS colour to r/g/b. Hex is parsed directly, since every built-in palette and
     * every validated custom colour is hex; anything else goes through the canvas, which normalises
     * keywords and rgb() for us when it is available.
     */
    function toRgb(color) {
        if (!color) {
            return null;
        }
        var direct = parseHex(color);
        if (direct) {
            return direct;
        }
        if (!measureContext) {
            return null;
        }
        var normalised;
        try {
            measureContext.fillStyle = "#000000";
            measureContext.fillStyle = color;
            normalised = measureContext.fillStyle;
        } catch (error) {
            return null;
        }
        if (typeof normalised !== "string") {
            return null; // no usable canvas; fall back to white ink
        }
        var parsed = parseHex(normalised);
        if (parsed) {
            return parsed;
        }
        var match = normalised.match(/rgba?\(([^)]+)\)/);
        if (!match) {
            return null;
        }
        var parts = match[1].split(",");
        return { r: parseFloat(parts[0]), g: parseFloat(parts[1]), b: parseFloat(parts[2]) };
    }

    /** #rgb, #rrggbb and #rrggbbaa; null for anything else. Alpha is ignored, tiles are opaque. */
    function parseHex(color) {
        var match = /^#([0-9a-fA-F]{3,8})$/.exec(String(color).trim());
        if (!match) {
            return null;
        }
        var hex = match[1];
        if (hex.length === 3 || hex.length === 4) {
            hex = hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        } else if (hex.length !== 6 && hex.length !== 8) {
            return null;
        }
        return {
            r: parseInt(hex.slice(0, 2), 16),
            g: parseInt(hex.slice(2, 4), 16),
            b: parseInt(hex.slice(4, 6), 16),
        };
    }

    function relativeLuminance(rgb) {
        var channels = [rgb.r, rgb.g, rgb.b].map(function (value) {
            var channel = value / 255;
            return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }
})();
