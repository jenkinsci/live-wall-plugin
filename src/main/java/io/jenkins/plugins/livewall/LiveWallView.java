package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ListView;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;

/**
 * A view that renders its jobs as a wall of coloured tiles, meant to be left on a television.
 *
 * <p>It extends {@link ListView} so that job selection, folder recursion, the include regex and
 * job filters all behave exactly as people already expect; everything this class adds is about how
 * the result is <em>drawn</em>. The rendering itself lives in the browser: the page ships a static
 * shell and then polls {@code wallData} for a small JSON document, so a wall can sit open for weeks
 * without leaking DOM or hammering the controller with full page loads.
 *
 * <p>Three deliberate departures from the older build-monitor style of wall:
 * <ul>
 *   <li><b>No pagination.</b> Tiles are scaled to fit the screen, so nothing is ever on the page
 *       you are not looking at. Walls too large to shrink can scroll continuously instead.</li>
 *   <li><b>No per-tile detail.</b> A tile carries the job name and a colour. Anything else is off
 *       by default, because at four metres you cannot read a commit message anyway.</li>
 *   <li><b>Palettes designed for large panels.</b> Saturated fills and automatically contrasting
 *       ink, rather than the washed-out pastels that disappear under office lighting.</li>
 * </ul>
 */
public class LiveWallView extends ListView {

    static final int MIN_REFRESH_SECONDS = 2;
    static final int MAX_REFRESH_SECONDS = 3600;
    static final int DEFAULT_REFRESH_SECONDS = 6;

    static final int MIN_TILE_HEIGHT_FLOOR = 24;
    static final int MIN_TILE_HEIGHT_CEILING = 600;
    static final int DEFAULT_MIN_TILE_HEIGHT = 110;

    static final int MAX_TILE_GAP = 64;
    /** Zero by default: tiles meet, and the wall reads as one surface rather than scattered cards. */
    static final int DEFAULT_TILE_GAP = 0;

    static final int MAX_SEAM_WIDTH = 12;
    /** A hairline of background colour drawn inside each tile, so that a run of same-coloured
     *  neighbours at zero gap is still countable instead of merging into one block. */
    static final int DEFAULT_SEAM_WIDTH = 2;

    /** A CSS hex colour, or a bare CSS colour keyword. Anything else is dropped on save. */
    private static final Pattern SAFE_COLOR =
            Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|[a-zA-Z]{3,24}");

    /** Separators left stranded by name rewriting. */
    private static final Pattern DANGLING_SEPARATORS = Pattern.compile("^[-_\\s/]+|[-_\\s/]+$");

    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("[-_\\s]{2,}");

    private Palette palette = Palette.VIVID;
    private TileShape shape = TileShape.ROUNDED;
    private TileAnimation animation = TileAnimation.PROGRESS;
    private Sizing sizing = Sizing.FIT;
    private SortBy sortBy = SortBy.RUNNING;
    private StatusScope statusScope = StatusScope.ALL;

    private Packing packing = Packing.INTERLOCK;

    private int refreshSeconds = DEFAULT_REFRESH_SECONDS;
    private int minTileHeight = DEFAULT_MIN_TILE_HEIGHT;
    private int tileGap = DEFAULT_TILE_GAP;

    /** Boxed because zero is a legal width, so null is the only way to mean "never set". XStream
     *  does not run field initialisers, and a config.xml that omits this must still get a seam. */
    @CheckForNull
    private Integer seamWidth;

    /* Boxed, both of them, because their default is true and XStream does not run field
     * initialisers: a config.xml that omits the element -- which is what JCasC and any scripted
     * view creation produce -- would otherwise silently switch them off. */
    @CheckForNull
    private Boolean showHeader;

    @CheckForNull
    private Boolean showFolderPath;

    private boolean showBuildNumber;
    private boolean hideDisabled;
    private boolean burnInProtection;

    @CheckForNull
    private String includeNames;

    @CheckForNull
    private String excludeNames;

    private transient volatile NameFilter nameFilter;

    @CheckForNull
    private String nameReplaceRegex;

    @CheckForNull
    private String customBackground;

    @CheckForNull
    private String customSuccess;

    @CheckForNull
    private String customFailure;

    @CheckForNull
    private String customUnstable;

    @CheckForNull
    private String customAborted;

    @CheckForNull
    private String customIdle;

    private transient volatile Pattern nameReplacePattern;

    @DataBoundConstructor
    public LiveWallView(String name) {
        super(name);
    }

    public LiveWallView(String name, ViewGroup owner) {
        super(name, owner);
    }

    @Override
    protected Object readResolve() {
        super.readResolve();
        if (palette == null) {
            palette = Palette.VIVID;
        }
        if (shape == null) {
            shape = TileShape.ROUNDED;
        }
        if (animation == null) {
            animation = TileAnimation.PROGRESS;
        }
        if (sizing == null) {
            sizing = Sizing.FIT;
        }
        if (sortBy == null) {
            sortBy = SortBy.RUNNING;
        }
        if (statusScope == null) {
            statusScope = StatusScope.ALL;
        }
        if (packing == null) {
            packing = Packing.INTERLOCK;
        }
        tileGap = clampRange(tileGap, 0, MAX_TILE_GAP);
        if (seamWidth != null) {
            seamWidth = clampRange(seamWidth, 0, MAX_SEAM_WIDTH);
        }
        refreshSeconds = clamp(refreshSeconds, MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS);
        minTileHeight = clamp(minTileHeight, MIN_TILE_HEIGHT_FLOOR, MIN_TILE_HEIGHT_CEILING, DEFAULT_MIN_TILE_HEIGHT);
        compileNameReplacePattern();
        compileNameFilter();
        return this;
    }

    // ---------------------------------------------------------------- configuration

    @NonNull
    public Palette getPalette() {
        return palette == null ? Palette.VIVID : palette;
    }

    @DataBoundSetter
    public void setPalette(@CheckForNull String palette) {
        this.palette = WallOption.parse(Palette.class, palette, Palette.VIVID);
    }

    @NonNull
    public TileShape getShape() {
        return shape == null ? TileShape.ROUNDED : shape;
    }

    @DataBoundSetter
    public void setShape(@CheckForNull String shape) {
        this.shape = WallOption.parse(TileShape.class, shape, TileShape.ROUNDED);
    }

    @NonNull
    public TileAnimation getAnimation() {
        return animation == null ? TileAnimation.PROGRESS : animation;
    }

    @DataBoundSetter
    public void setAnimation(@CheckForNull String animation) {
        this.animation = WallOption.parse(TileAnimation.class, animation, TileAnimation.PROGRESS);
    }

    @NonNull
    public Sizing getSizing() {
        return sizing == null ? Sizing.FIT : sizing;
    }

    @DataBoundSetter
    public void setSizing(@CheckForNull String sizing) {
        this.sizing = WallOption.parse(Sizing.class, sizing, Sizing.FIT);
    }

    @NonNull
    public SortBy getSortBy() {
        return sortBy == null ? SortBy.RUNNING : sortBy;
    }

    @DataBoundSetter
    public void setSortBy(@CheckForNull String sortBy) {
        this.sortBy = WallOption.parse(SortBy.class, sortBy, SortBy.RUNNING);
    }

    @NonNull
    public StatusScope getStatusScope() {
        return statusScope == null ? StatusScope.ALL : statusScope;
    }

    @DataBoundSetter
    public void setStatusScope(@CheckForNull String statusScope) {
        this.statusScope = WallOption.parse(StatusScope.class, statusScope, StatusScope.ALL);
    }

    @NonNull
    public Packing getPacking() {
        return packing == null ? Packing.INTERLOCK : packing;
    }

    @DataBoundSetter
    public void setPacking(@CheckForNull String packing) {
        this.packing = WallOption.parse(Packing.class, packing, Packing.INTERLOCK);
    }

    /** Space between tiles in pixels. Zero by design: a wall, not a set of cards. */
    public int getTileGap() {
        return tileGap;
    }

    @DataBoundSetter
    public void setTileGap(int tileGap) {
        this.tileGap = clampRange(tileGap, 0, MAX_TILE_GAP);
    }

    /** Width of the hairline of background colour drawn inside each tile's outline. */
    public int getSeamWidth() {
        return seamWidth == null ? DEFAULT_SEAM_WIDTH : seamWidth;
    }

    @DataBoundSetter
    public void setSeamWidth(int seamWidth) {
        this.seamWidth = clampRange(seamWidth, 0, MAX_SEAM_WIDTH);
    }

    public int getRefreshSeconds() {
        return refreshSeconds;
    }

    @DataBoundSetter
    public void setRefreshSeconds(int refreshSeconds) {
        this.refreshSeconds = clamp(refreshSeconds, MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS);
    }

    public int getMinTileHeight() {
        return minTileHeight;
    }

    @DataBoundSetter
    public void setMinTileHeight(int minTileHeight) {
        this.minTileHeight =
                clamp(minTileHeight, MIN_TILE_HEIGHT_FLOOR, MIN_TILE_HEIGHT_CEILING, DEFAULT_MIN_TILE_HEIGHT);
    }

    public boolean isShowHeader() {
        return showHeader == null || showHeader;
    }

    @DataBoundSetter
    public void setShowHeader(boolean showHeader) {
        this.showHeader = showHeader;
    }

    public boolean isShowFolderPath() {
        return showFolderPath == null || showFolderPath;
    }

    @DataBoundSetter
    public void setShowFolderPath(boolean showFolderPath) {
        this.showFolderPath = showFolderPath;
    }

    public boolean isShowBuildNumber() {
        return showBuildNumber;
    }

    @DataBoundSetter
    public void setShowBuildNumber(boolean showBuildNumber) {
        this.showBuildNumber = showBuildNumber;
    }

    public boolean isHideDisabled() {
        return hideDisabled;
    }

    @DataBoundSetter
    public void setHideDisabled(boolean hideDisabled) {
        this.hideDisabled = hideDisabled;
    }

    public boolean isBurnInProtection() {
        return burnInProtection;
    }

    @DataBoundSetter
    public void setBurnInProtection(boolean burnInProtection) {
        this.burnInProtection = burnInProtection;
    }

    @CheckForNull
    public String getIncludeNames() {
        return includeNames;
    }

    @DataBoundSetter
    public void setIncludeNames(@CheckForNull String includeNames) {
        this.includeNames = fixEmpty(includeNames);
        compileNameFilter();
    }

    @CheckForNull
    public String getExcludeNames() {
        return excludeNames;
    }

    @DataBoundSetter
    public void setExcludeNames(@CheckForNull String excludeNames) {
        this.excludeNames = fixEmpty(excludeNames);
        compileNameFilter();
    }

    @CheckForNull
    public String getNameReplaceRegex() {
        return nameReplaceRegex;
    }

    @DataBoundSetter
    public void setNameReplaceRegex(@CheckForNull String nameReplaceRegex) {
        this.nameReplaceRegex = fixEmpty(nameReplaceRegex);
        compileNameReplacePattern();
    }

    @CheckForNull
    public String getCustomBackground() {
        return customBackground;
    }

    @DataBoundSetter
    public void setCustomBackground(@CheckForNull String customBackground) {
        this.customBackground = sanitizeColor(customBackground);
    }

    @CheckForNull
    public String getCustomSuccess() {
        return customSuccess;
    }

    @DataBoundSetter
    public void setCustomSuccess(@CheckForNull String customSuccess) {
        this.customSuccess = sanitizeColor(customSuccess);
    }

    @CheckForNull
    public String getCustomFailure() {
        return customFailure;
    }

    @DataBoundSetter
    public void setCustomFailure(@CheckForNull String customFailure) {
        this.customFailure = sanitizeColor(customFailure);
    }

    @CheckForNull
    public String getCustomUnstable() {
        return customUnstable;
    }

    @DataBoundSetter
    public void setCustomUnstable(@CheckForNull String customUnstable) {
        this.customUnstable = sanitizeColor(customUnstable);
    }

    @CheckForNull
    public String getCustomAborted() {
        return customAborted;
    }

    @DataBoundSetter
    public void setCustomAborted(@CheckForNull String customAborted) {
        this.customAborted = sanitizeColor(customAborted);
    }

    @CheckForNull
    public String getCustomIdle() {
        return customIdle;
    }

    @DataBoundSetter
    public void setCustomIdle(@CheckForNull String customIdle) {
        this.customIdle = sanitizeColor(customIdle);
    }

    /**
     * The custom colours that survived validation, keyed by {@link #CUSTOM_COLOR_KEYS}. Emitted as
     * {@code data-color-*} attributes and applied by the browser as CSS custom properties, so an
     * unset colour simply falls through to the built-in palette.
     */
    @NonNull
    public Map<String, String> getCustomColors() {
        Map<String, String> colors = new LinkedHashMap<>();
        putIfSet(colors, "background", customBackground);
        putIfSet(colors, "success", customSuccess);
        putIfSet(colors, "failure", customFailure);
        putIfSet(colors, "unstable", customUnstable);
        putIfSet(colors, "aborted", customAborted);
        putIfSet(colors, "idle", customIdle);
        return colors;
    }

    /**
     * What to say when the wall has nothing on it. A problems-only wall being empty is the good
     * outcome, and should read like one rather than like a misconfigured view.
     */
    @NonNull
    public String getEmptyMessage() {
        return getStatusScope() == StatusScope.ALL
                ? Messages.LiveWallView_NoJobs()
                : Messages.LiveWallView_NothingWrong();
    }

    // ------------------------------------------------------- option lists for the forms

    public Palette[] getPaletteOptions() {
        return Palette.values();
    }

    public TileShape[] getShapeOptions() {
        return TileShape.values();
    }

    public TileAnimation[] getAnimationOptions() {
        return TileAnimation.values();
    }

    public Packing[] getPackingOptions() {
        return Packing.values();
    }

    public SortBy[] getSortByOptions() {
        return SortBy.values();
    }

    // ---------------------------------------------------------------- the wall itself

    /**
     * Snapshots every job in the view that the current user may see, in the configured order.
     *
     * <p>Cheap by design: for a job that is not building this touches nothing beyond the last build
     * that rendering any Jenkins page would touch anyway, which is what makes a two second refresh
     * across several hundred jobs affordable.
     */
    @NonNull
    public List<Tile> getTiles() {
        return getTiles(getSortBy());
    }

    /**
     * Snapshots the wall in a given order. The order is a parameter rather than always the saved
     * one so that the preview controls, and a kiosk URL, can try an ordering without anyone having
     * to save it first.
     */
    @NonNull
    public List<Tile> getTiles(@NonNull SortBy sort) {
        return getTiles(sort, nameFilter());
    }

    /**
     * Snapshots the wall in a given order, keeping only the jobs a filter accepts.
     *
     * <p>Order and filter are parameters rather than always the saved ones so that one view can
     * feed several screens: the office TV shows everything, the team's monitor adds
     * {@code ?exclude=dev-*}, and nobody has to maintain three views that drift apart.
     */
    @NonNull
    public List<Tile> getTiles(@NonNull SortBy sort, @NonNull NameFilter filter) {
        StatusScope scope = getStatusScope();
        Set<String> queuedJobs = queuedJobNames();
        List<Tile> tiles = new ArrayList<>();

        for (TopLevelItem item : getItems()) {
            if (!(item instanceof Job)) {
                continue; // folders and other non-buildable items have no status to show
            }
            Job<?, ?> job = (Job<?, ?>) item;
            if (!filter.accepts(job.getFullName(), job.getDisplayName(), relativeDisplayName(job))) {
                continue;
            }
            JobStatus status = JobStatus.of(job.getIconColor());
            if (hideDisabled && status == JobStatus.DISABLED) {
                continue;
            }

            Run<?, ?> lastBuild = job.getLastBuild();
            boolean building = lastBuild != null && lastBuild.isBuilding();
            boolean queued = queuedJobs.contains(job.getFullName());
            if (!scope.accepts(status, building || queued)) {
                continue;
            }

            long startedAt = 0L;
            long estimatedDuration = -1L;
            if (building) {
                startedAt = lastBuild.getTimeInMillis();
                estimatedDuration = lastBuild.getEstimatedDuration();
            }

            // Only the "most recently built" order needs this, and it costs another build load.
            long completedAt = 0L;
            if (sort == SortBy.RECENT) {
                Run<?, ?> lastCompleted = job.getLastCompletedBuild();
                completedAt = lastCompleted == null ? 0L : lastCompleted.getTimeInMillis();
            }

            tiles.add(new Tile(
                    label(job),
                    job.getFullName(),
                    canonicalUrl(job),
                    status,
                    building,
                    queued,
                    lastBuild == null ? 0 : lastBuild.getNumber(),
                    startedAt,
                    estimatedDuration,
                    completedAt));
        }

        Comparator<Tile> order = comparator(sort);
        if (order != null) {
            tiles.sort(order);
        }
        return tiles;
    }

    /**
     * Full names of every job with something waiting in the build queue, gathered once so that a
     * wall of several hundred jobs does not rescan the queue for each of them.
     */
    @NonNull
    private static Set<String> queuedJobNames() {
        Set<String> names = new HashSet<>();
        for (Queue.Item queued : Jenkins.get().getQueue().getItems()) {
            if (queued.task instanceof Job<?, ?> job) {
                names.add(job.getFullName());
            }
        }
        return names;
    }

    /**
     * The standalone full-screen page, at {@code <view>/wall}. This is the URL a television points
     * at: no Jenkins header, no side panel, nothing but the wall.
     */
    @GET
    public void doWall(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        checkPermission(View.READ);
        req.getView(this, "kiosk.jelly").forward(req, rsp);
    }

    /**
     * Serves the wall contents. Polled by {@code wall.js}; also a perfectly usable API.
     *
     * @param sortBy optional ordering for this request only, so that the preview controls and a
     *     kiosk URL can try one without changing what is saved. Anything unrecognised falls back to
     *     the view's own setting.
     */
    @GET
    public void doWallData(
            StaplerResponse2 rsp,
            @QueryParameter String sortBy,
            @QueryParameter String include,
            @QueryParameter String exclude)
            throws IOException {
        checkPermission(View.READ);

        // Each override stands on its own, so ?exclude=dev-* narrows a wall without discarding
        // whatever include list the view already has. Narrowing only: these can never reveal a job
        // the caller could not already see.
        NameFilter filter = include == null && exclude == null
                ? nameFilter()
                : NameFilter.of(include != null ? include : includeNames, exclude != null ? exclude : excludeNames);

        JSONArray array = new JSONArray();
        for (Tile tile : getTiles(WallOption.parse(SortBy.class, sortBy, getSortBy()), filter)) {
            array.add(tile.toJson());
        }
        JSONObject payload = new JSONObject();
        payload.element("generatedAt", System.currentTimeMillis());
        payload.element("tiles", array);

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store, must-revalidate");
        rsp.getWriter().write(payload.toString());
    }

    @Override
    protected void submit(StaplerRequest2 req) throws IOException, ServletException, FormException {
        super.submit(req);
        JSONObject form = req.getSubmittedForm();

        setPalette(form.optString("palette", null));
        setShape(form.optString("shape", null));
        setAnimation(form.optString("animation", null));
        setSizing(form.optString("sizing", null));
        setPacking(form.optString("packing", null));
        setSortBy(form.optString("sortBy", null));
        setStatusScope(form.optString("statusScope", null));

        setRefreshSeconds(readInt(form, "refreshSeconds", DEFAULT_REFRESH_SECONDS));
        setMinTileHeight(readInt(form, "minTileHeight", DEFAULT_MIN_TILE_HEIGHT));
        setTileGap(readInt(form, "tileGap", DEFAULT_TILE_GAP));
        setSeamWidth(readInt(form, "seamWidth", DEFAULT_SEAM_WIDTH));

        // Checkboxes are always present in a submitted form, so an absent key means "off" rather
        // than "leave it alone" -- otherwise a box could be ticked but never unticked.
        setShowHeader(readBoolean(form, "showHeader"));
        setShowFolderPath(readBoolean(form, "showFolderPath"));
        setShowBuildNumber(readBoolean(form, "showBuildNumber"));
        setHideDisabled(readBoolean(form, "hideDisabled"));
        setBurnInProtection(readBoolean(form, "burnInProtection"));

        setIncludeNames(form.optString("includeNames", null));
        setExcludeNames(form.optString("excludeNames", null));
        setNameReplaceRegex(form.optString("nameReplaceRegex", null));

        setCustomBackground(form.optString("customBackground", null));
        setCustomSuccess(form.optString("customSuccess", null));
        setCustomFailure(form.optString("customFailure", null));
        setCustomUnstable(form.optString("customUnstable", null));
        setCustomAborted(form.optString("customAborted", null));
        setCustomIdle(form.optString("customIdle", null));
    }

    // ---------------------------------------------------------------- helpers

    @CheckForNull
    private static Comparator<Tile> comparator(@NonNull SortBy sortBy) {
        Comparator<Tile> byLabel = Comparator.comparing(tile -> tile.label().toLowerCase(Locale.ROOT));
        switch (sortBy) {
            case NAME:
                return byLabel.thenComparing(Tile::fullName);
            case RUNNING:
                // Building, then queued, then everything else worst-first, so the wall answers
                // "what is happening right now" before "what is broken".
                return Comparator.comparingInt((Tile tile) -> tile.building() ? 0 : tile.queued() ? 1 : 2)
                        .thenComparingInt(tile -> tile.status().getSeverity())
                        .thenComparing(byLabel);
            case STATUS:
                return Comparator.comparingInt((Tile tile) -> tile.status().getSeverity())
                        .thenComparing(byLabel);
            case SUCCESS:
                return Comparator.comparingInt((Tile tile) -> -tile.status().getSeverity())
                        .thenComparing(byLabel);
            case RECENT:
                return Comparator.comparingLong((Tile tile) -> Math.max(tile.startedAt(), tile.completedAt()))
                        .reversed()
                        .thenComparing(byLabel);
            case VIEW_ORDER:
            default:
                return null;
        }
    }

    /**
     * The job's own URL relative to the Jenkins root.
     *
     * <p>Deliberately not {@link Job#getUrl()}: inside a request that one rewrites itself against
     * the nearest ancestor, so a tile fetched through {@code /view/x/wallData} would come back as
     * {@code view/x/job/y/}. The wall is a long-lived page whose links should not depend on which
     * URL happened to deliver the data.
     */
    @NonNull
    private static String canonicalUrl(@NonNull Job<?, ?> job) {
        return job.getParent().getUrl() + job.getShortUrl();
    }

    /**
     * The text drawn on the tile: the job's display name, optionally prefixed with the folders
     * between it and this view, and then passed through the configured name rewrite.
     */
    @NonNull
    private String label(@NonNull Job<?, ?> job) {
        String base = isShowFolderPath() ? relativeDisplayName(job) : job.getDisplayName();
        return rewriteName(base);
    }

    /**
     * Renders {@code job}'s display name qualified by any folders between it and the item group
     * that owns this view, so that the twenty branches called "main" on a wall of multibranch
     * pipelines stay distinguishable.
     */
    @NonNull
    private String relativeDisplayName(@NonNull Job<?, ?> job) {
        ItemGroup<? extends TopLevelItem> stopAt = getOwnerItemGroup();
        Deque<String> segments = new ArrayDeque<>();
        Item current = job;
        while (current != null) {
            segments.addFirst(current.getDisplayName());
            ItemGroup<?> parent = current.getParent();
            if (parent == stopAt || !(parent instanceof Item)) {
                break;
            }
            current = (Item) parent;
        }
        return String.join("/", segments);
    }

    /**
     * Strips everything matching the configured regex out of a job name. Falls back to the original
     * when the pattern would leave nothing behind, since a blank tile helps nobody.
     */
    @NonNull
    String rewriteName(@NonNull String name) {
        Pattern pattern = nameReplacePattern;
        if (pattern == null) {
            return name;
        }
        String rewritten = pattern.matcher(name).replaceAll("");
        rewritten = REPEATED_SEPARATORS.matcher(rewritten).replaceAll("-");
        rewritten = DANGLING_SEPARATORS.matcher(rewritten).replaceAll("");
        return rewritten.isEmpty() ? name : rewritten;
    }

    @NonNull
    private NameFilter nameFilter() {
        NameFilter current = nameFilter;
        if (current == null) {
            current = NameFilter.of(includeNames, excludeNames);
            nameFilter = current;
        }
        return current;
    }

    private void compileNameFilter() {
        nameFilter = NameFilter.of(includeNames, excludeNames);
    }

    private void compileNameReplacePattern() {
        String regex = nameReplaceRegex;
        if (regex == null) {
            nameReplacePattern = null;
            return;
        }
        try {
            nameReplacePattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            // Validated in the form; a bad pattern saved another way just means no rewriting.
            nameReplacePattern = null;
        }
    }

    /** Number fields arrive as strings from the form and as numbers from elsewhere; take either. */
    private static int readInt(JSONObject form, String key, int fallback) {
        Object value = form.opt(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean readBoolean(JSONObject form, String key) {
        Object value = form.opt(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }

    private static void putIfSet(Map<String, String> map, String key, @CheckForNull String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    @CheckForNull
    private static String fixEmpty(@CheckForNull String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @CheckForNull
    static String sanitizeColor(@CheckForNull String value) {
        String trimmed = fixEmpty(value);
        if (trimmed == null || !SAFE_COLOR.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /** Plain clamp, for settings where zero is a real value rather than "unset". */
    private static int clampRange(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Keeps a number inside its range, treating "absent" (zero or negative) as "use the default". */
    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    @Extension
    @Symbol("liveWall")
    public static class DescriptorImpl extends ViewDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.LiveWallView_DisplayName();
        }

        /**
         * Guards the form-support endpoints below.
         *
         * <p>Everything they return is either a fixed list of enum constants or a restatement of
         * the caller's own input, so none of it is private. They are guarded anyway: they exist to
         * serve someone editing a wall, there is no reason for them to answer anybody else, and a
         * reviewer should not have to reason about whether each one leaks something.
         *
         * <p>The view is the nearest ancestor when these are called from a view's configuration
         * page. Where there is none, this falls back to requiring overall administration, which is
         * the conventional treatment for a descriptor reached outside any object.
         */
        private static void checkFormAccess(@CheckForNull View view) {
            if (view != null) {
                view.checkPermission(View.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillPaletteItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(Palette.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillShapeItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(TileShape.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillAnimationItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(TileAnimation.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillSizingItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(Sizing.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillPackingItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(Packing.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillSortByItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(SortBy.class);
        }

        // lgtm[jenkins/csrf] a fixed list of enum constants: no side effects, nothing to forge
        public ListBoxModel doFillStatusScopeItems(@AncestorInPath View view) {
            checkFormAccess(view);
            return WallOption.items(StatusScope.class);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckRefreshSeconds(@AncestorInPath View view, @QueryParameter int value) {
            checkFormAccess(view);
            if (value < MIN_REFRESH_SECONDS) {
                return FormValidation.error(Messages.LiveWallView_RefreshTooFast(MIN_REFRESH_SECONDS));
            }
            if (value > MAX_REFRESH_SECONDS) {
                return FormValidation.error(Messages.LiveWallView_RefreshTooSlow(MAX_REFRESH_SECONDS));
            }
            if (value < 5) {
                return FormValidation.warning(Messages.LiveWallView_RefreshBusy());
            }
            return FormValidation.ok();
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckMinTileHeight(@AncestorInPath View view, @QueryParameter int value) {
            checkFormAccess(view);
            if (value < MIN_TILE_HEIGHT_FLOOR || value > MIN_TILE_HEIGHT_CEILING) {
                return FormValidation.error(
                        Messages.LiveWallView_TileHeightRange(MIN_TILE_HEIGHT_FLOOR, MIN_TILE_HEIGHT_CEILING));
            }
            return FormValidation.ok();
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckTileGap(@AncestorInPath View view, @QueryParameter int value) {
            checkFormAccess(view);
            if (value < 0 || value > MAX_TILE_GAP) {
                return FormValidation.error(Messages.LiveWallView_GapRange(MAX_TILE_GAP));
            }
            return FormValidation.ok();
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckSeamWidth(@AncestorInPath View view, @QueryParameter int value) {
            checkFormAccess(view);
            if (value < 0 || value > MAX_SEAM_WIDTH) {
                return FormValidation.error(Messages.LiveWallView_SeamRange(MAX_SEAM_WIDTH));
            }
            if (value == 0) {
                return FormValidation.warning(Messages.LiveWallView_SeamZero());
            }
            return FormValidation.ok();
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckIncludeNames(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return describeFilter(value, Messages.LiveWallView_IncludeAll());
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckExcludeNames(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return describeFilter(value, Messages.LiveWallView_ExcludeNone());
        }

        /** Echoes the filter back in words, so a typo shows up before anyone walks to the TV. */
        private static FormValidation describeFilter(@CheckForNull String value, String whenEmpty) {
            String described = NameFilter.describe(value);
            return FormValidation.ok(described.isEmpty() ? whenEmpty : described);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckNameReplaceRegex(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            String regex = fixEmpty(value);
            if (regex == null) {
                return FormValidation.ok();
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                return FormValidation.error(Messages.LiveWallView_BadRegex(e.getDescription()));
            }
            return FormValidation.ok();
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomBackground(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomSuccess(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomFailure(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomUnstable(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomAborted(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        // lgtm[jenkins/csrf] validates a value and returns a message: no side effects, nothing to forge
        public FormValidation doCheckCustomIdle(@AncestorInPath View view, @QueryParameter String value) {
            checkFormAccess(view);
            return checkColor(value);
        }

        private static FormValidation checkColor(@CheckForNull String value) {
            if (fixEmpty(value) == null || sanitizeColor(value) != null) {
                return FormValidation.ok();
            }
            return FormValidation.error(Messages.LiveWallView_BadColor());
        }
    }
}
