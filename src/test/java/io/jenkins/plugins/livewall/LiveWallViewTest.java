package io.jenkins.plugins.livewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.View;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LiveWallViewTest {

    private LiveWallView createView(JenkinsRule r, String name) throws Exception {
        LiveWallView view = new LiveWallView(name, r.jenkins);
        r.jenkins.addView(view);
        return view;
    }

    @Test
    void defaultsAreTheOnesTheDocumentationPromises(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        assertSame(Palette.VIVID, view.getPalette());
        assertSame(TileShape.ROUNDED, view.getShape());
        assertSame(TileAnimation.PROGRESS, view.getAnimation());
        assertSame(Sizing.FIT, view.getSizing(), "fit, because pagination is the thing this replaces");
        assertSame(SortBy.RUNNING, view.getSortBy(), "what is happening now comes first");
        assertSame(StatusScope.ALL, view.getStatusScope());
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
        assertSame(Packing.INTERLOCK, view.getPacking(), "so a wall of hexagons is a honeycomb");
        assertEquals(0, view.getTileGap(), "tiles meet by default; the wall is one surface");
        assertEquals(
                LiveWallView.DEFAULT_SEAM_WIDTH,
                view.getSeamWidth(),
                "but a hairline keeps same-coloured neighbours countable");
        assertTrue(view.isShowHeader());
        assertTrue(view.isShowFolderPath());
        assertFalse(view.isShowBuildNumber(), "per-tile detail stays off unless asked for");
    }

    @Test
    void tilesCarryTheLastOutcomeOfEveryJobInTheView(JenkinsRule r) throws Exception {
        FreeStyleProject good = r.createFreeStyleProject("good");
        FreeStyleProject bad = r.createFreeStyleProject("bad");
        bad.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.buildAndAssertSuccess(good);
        r.assertBuildStatus(Result.FAILURE, bad.scheduleBuild2(0));

        LiveWallView view = createView(r, "wall");
        view.add(good);
        view.add(bad);

        List<Tile> tiles = view.getTiles();
        assertEquals(2, tiles.size());
        assertEquals(JobStatus.FAILURE, statusOf(tiles, "bad"));
        assertEquals(JobStatus.SUCCESS, statusOf(tiles, "good"));
    }

    @Test
    void jobsThatHaveNeverBuiltAreNotBuiltRatherThanFailing(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("fresh");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        List<Tile> tiles = view.getTiles();
        assertEquals(1, tiles.size());
        assertEquals(JobStatus.NOT_BUILT, tiles.get(0).status());
        assertEquals(0, tiles.get(0).buildNumber());
    }

    @Test
    void sortByStatusFloatsProblemsToTheTopLeft(JenkinsRule r) throws Exception {
        FreeStyleProject passing = r.createFreeStyleProject("aaa-passing");
        FreeStyleProject failing = r.createFreeStyleProject("zzz-failing");
        failing.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.buildAndAssertSuccess(passing);
        r.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        view.setSortBy("name");
        assertEquals(List.of("aaa-passing", "zzz-failing"), labels(view));

        view.setSortBy("status");
        assertEquals(List.of("zzz-failing", "aaa-passing"), labels(view), "failed first");

        view.setSortBy("success");
        assertEquals(List.of("aaa-passing", "zzz-failing"), labels(view), "passing first");
    }

    @Test
    void runningFirstLiftsABuildingJobAboveAFailingOne(JenkinsRule r) throws Exception {
        FreeStyleProject broken = r.createFreeStyleProject("zzz-broken");
        broken.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.assertBuildStatus(Result.FAILURE, broken.scheduleBuild2(0));

        FreeStyleProject slow = r.createFreeStyleProject("aaa-slow");
        slow.getBuildersList().add(new org.jvnet.hudson.test.SleepBuilder(60_000));
        slow.scheduleBuild2(0);

        long deadline = System.currentTimeMillis() + 30_000;
        while (!slow.isBuilding() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(slow.isBuilding(), "the slow job never started, so this test proves nothing");

        try {
            LiveWallView view = createView(r, "wall");
            view.setIncludeRegex(".*");

            assertEquals(
                    List.of("aaa-slow", "zzz-broken"),
                    labels(view),
                    "by default a job building right now outranks a failing one");

            view.setSortBy("status");
            assertEquals(List.of("zzz-broken", "aaa-slow"), labels(view), "failed first reverses that");
        } finally {
            slow.getLastBuild().doStop();
        }
    }

    @Test
    void problemsScopeLeavesAnEmptyWallWhenEverythingIsGreen(JenkinsRule r) throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("fine"));

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(1, view.getTiles().size());
        assertEquals(Messages.LiveWallView_NoJobs(), view.getEmptyMessage());

        view.setStatusScope("problems");

        assertTrue(view.getTiles().isEmpty(), "an empty problems wall is the good outcome");
        assertEquals(
                Messages.LiveWallView_NothingWrong(),
                view.getEmptyMessage(),
                "so it should read as good news rather than as a broken view");
    }

    @Test
    void disabledJobsCanBeHidden(JenkinsRule r) throws Exception {
        FreeStyleProject project = r.createFreeStyleProject("off");
        project.disable();

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(JobStatus.DISABLED, view.getTiles().get(0).status());
        view.setHideDisabled(true);
        assertTrue(view.getTiles().isEmpty());
    }

    @Test
    void foldersDoNotBecomeTilesButTheirJobsDo(JenkinsRule r) throws Exception {
        MockFolder folder = r.createFolder("team");
        folder.createProject(FreeStyleProject.class, "main");

        LiveWallView view = createView(r, "wall");
        view.setRecurse(true);
        view.setIncludeRegex(".*");

        List<Tile> tiles = view.getTiles();
        assertEquals(1, tiles.size(), "the folder itself has no status to show");
        assertEquals("team/main", tiles.get(0).label(), "folders qualify an otherwise ambiguous name");

        view.setShowFolderPath(false);
        assertEquals("main", view.getTiles().get(0).label());
    }

    @Test
    void theNameFilterPicksJobsWithoutARegularExpression(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("ci-decision-control");
        r.createFreeStyleProject("ci-decision-sandbox");
        r.createFreeStyleProject("drools-nightly");
        r.createFreeStyleProject("quarkus");

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(4, view.getTiles().size(), "no filter means every job");

        view.setIncludeNames("ci-*\ndrools");
        assertEquals(
                List.of("ci-decision-control", "ci-decision-sandbox", "drools-nightly"),
                labels(view),
                "a wildcard anchors, a plain word means contains");

        view.setExcludeNames("*-sandbox");
        assertEquals(List.of("ci-decision-control", "drools-nightly"), labels(view));

        view.setIncludeNames(null);
        view.setExcludeNames(null);
        assertEquals(4, view.getTiles().size(), "clearing the filter brings them back");
    }

    @Test
    void theWallDataEndpointCanBeAskedForADifferentOrder(JenkinsRule r) throws Exception {
        FreeStyleProject passing = r.createFreeStyleProject("aaa-passing");
        FreeStyleProject failing = r.createFreeStyleProject("zzz-failing");
        failing.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        r.buildAndAssertSuccess(passing);
        r.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");
        view.setSortBy("name");

        assertEquals(List.of("aaa-passing", "zzz-failing"), labelsFrom(r, "view/wall/wallData"));
        assertEquals(
                List.of("zzz-failing", "aaa-passing"),
                labelsFrom(r, "view/wall/wallData?sortBy=status"),
                "the override reorders this response only");
        assertSame(SortBy.NAME, view.getSortBy(), "and does not change what is saved");
    }

    @Test
    void theWallDataEndpointCanBeAskedToExcludeJobs(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("ci-decision");
        r.createFreeStyleProject("dev-scratch");
        r.createFreeStyleProject("dev-spike");

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        assertEquals(3, labelsFrom(r, "view/wall/wallData").size());
        assertEquals(
                List.of("ci-decision"),
                labelsFrom(r, "view/wall/wallData?exclude=dev-*"),
                "one screen can drop the development jobs without a view of its own");
        assertNull(view.getExcludeNames(), "and the view itself is unchanged");

        // Each override stands alone: an exclude on the URL keeps the view's own include list.
        view.setIncludeNames("dev-*");
        assertEquals(2, labelsFrom(r, "view/wall/wallData").size());
        assertEquals(
                List.of("dev-spike"),
                labelsFrom(r, "view/wall/wallData?exclude=*scratch"),
                "the saved include still applies");
    }

    @Test
    void nameRewritingStripsTheNoiseAndNeverBlanksATile(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("ci-decision-control-pipeline");

        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");
        view.setNameReplaceRegex("^ci-|-pipeline$");

        assertEquals("decision-control", view.getTiles().get(0).label());

        // A pattern that would erase the whole name leaves the job identifiable instead.
        view.setNameReplaceRegex(".*");
        assertEquals("ci-decision-control-pipeline", view.getTiles().get(0).label());
    }

    @Test
    void anInvalidRewritePatternIsIgnoredRatherThanBreakingTheWall(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("job");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        view.setNameReplaceRegex("([unclosed");

        assertEquals("job", view.getTiles().get(0).label());
    }

    @Test
    void theFormSupportEndpointsRefuseSomeoneWhoCannotConfigureTheView(JenkinsRule r) throws Exception {
        createView(r, "wall");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));

        // A reader can look at the wall but has no business driving its configuration form.
        JenkinsRule.WebClient reader = r.createWebClient().login("reader");
        reader.setThrowExceptionOnFailingStatusCode(false);
        assertEquals(
                403,
                reader.goTo(
                                "view/wall/descriptorByName/io.jenkins.plugins.livewall.LiveWallView/fillPaletteItems",
                                null)
                        .getWebResponse()
                        .getStatusCode(),
                "populating a drop-down needs permission to configure the view");
        assertEquals(
                403,
                reader.goTo(
                                "view/wall/descriptorByName/io.jenkins.plugins.livewall.LiveWallView/checkRefreshSeconds?value=6",
                                null)
                        .getWebResponse()
                        .getStatusCode(),
                "and so does validating a field");

        // Somebody who can configure it still gets a working form.
        JenkinsRule.WebClient admin = r.createWebClient().login("admin");
        assertEquals(
                200,
                admin.goTo("view/wall/descriptorByName/io.jenkins.plugins.livewall.LiveWallView/fillPaletteItems", null)
                        .getWebResponse()
                        .getStatusCode(),
                "the guard must not break the form for the people who need it");
    }

    @Test
    void colourFieldsRejectAnythingThatIsNotAColour(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setCustomFailure("#ff0055");
        assertEquals("#ff0055", view.getCustomFailure());

        view.setCustomFailure("tomato");
        assertEquals("tomato", view.getCustomFailure());

        // Anything that could escape the attribute and become markup is dropped outright.
        view.setCustomFailure("red\" onload=\"alert(1)");
        assertNull(view.getCustomFailure());

        view.setCustomFailure("url(https://example.invalid/x.png)");
        assertNull(view.getCustomFailure());
    }

    @Test
    void refreshIntervalIsClampedToSomethingAControllerCanSurvive(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setRefreshSeconds(1);
        assertEquals(LiveWallView.MIN_REFRESH_SECONDS, view.getRefreshSeconds());

        view.setRefreshSeconds(999999);
        assertEquals(LiveWallView.MAX_REFRESH_SECONDS, view.getRefreshSeconds());

        view.setRefreshSeconds(0);
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
    }

    @Test
    void spacingAcceptsZeroAndClampsTheRest(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        // Zero is a real value here, not "unset" -- it is the default, and the other numeric
        // settings on this view deliberately treat zero as "give me the default" instead.
        view.setTileGap(0);
        assertEquals(0, view.getTileGap());
        view.setSeamWidth(0);
        assertEquals(0, view.getSeamWidth());

        view.setTileGap(-5);
        assertEquals(0, view.getTileGap());
        view.setTileGap(9999);
        assertEquals(LiveWallView.MAX_TILE_GAP, view.getTileGap());
        view.setSeamWidth(9999);
        assertEquals(LiveWallView.MAX_SEAM_WIDTH, view.getSeamWidth());
    }

    @Test
    void unknownOptionIdsFallBackInsteadOfThrowing(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");

        view.setPalette("no-such-palette");
        assertSame(Palette.VIVID, view.getPalette());

        // Enum constant names are accepted too, so a hand-edited config.xml still loads.
        view.setPalette("NEON");
        assertSame(Palette.NEON, view.getPalette());
        view.setShape("octagon");
        assertSame(TileShape.OCTAGON, view.getShape());
    }

    @Test
    void aMinimalConfigXmlLoadsWithEveryDefaultFilledIn(JenkinsRule r) throws Exception {
        // XStream does not run field initialisers, so everything a hand-written or scripted
        // config.xml leaves out has to be supplied by readResolve(). scripts/demo.sh creates its
        // view exactly like this.
        r.buildAndAssertSuccess(r.createFreeStyleProject("seeded"));
        String xml = "<io.jenkins.plugins.livewall.LiveWallView>"
                + "<name>Live Wall</name>"
                + "<includeRegex>.*</includeRegex>"
                + "</io.jenkins.plugins.livewall.LiveWallView>";

        View created =
                View.createViewFromXML("Live Wall", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        r.jenkins.addView(created);

        LiveWallView view = (LiveWallView) r.jenkins.getView("Live Wall");
        assertNotNull(view);
        assertSame(Palette.VIVID, view.getPalette());
        assertSame(TileShape.ROUNDED, view.getShape());
        assertSame(TileAnimation.PROGRESS, view.getAnimation());
        assertSame(Sizing.FIT, view.getSizing());
        assertSame(SortBy.RUNNING, view.getSortBy(), "what is happening now comes first");
        assertSame(StatusScope.ALL, view.getStatusScope());
        assertSame(Packing.INTERLOCK, view.getPacking());
        assertEquals(LiveWallView.DEFAULT_REFRESH_SECONDS, view.getRefreshSeconds());
        assertEquals(LiveWallView.DEFAULT_MIN_TILE_HEIGHT, view.getMinTileHeight());
        assertEquals(LiveWallView.DEFAULT_SEAM_WIDTH, view.getSeamWidth());
        // Booleans that default to true are the easy ones to get wrong here, because XStream
        // leaves an omitted element as false rather than running the field initialiser.
        assertTrue(view.isShowHeader(), "a scripted view still gets its header");
        assertTrue(view.isShowFolderPath());
        assertFalse(view.isShowBuildNumber());
        assertFalse(view.isHideDisabled());
        assertFalse(view.isBurnInProtection());
        assertEquals(List.of("seeded"), labels(view), "and the include regex still works");
    }

    @Test
    void wallDataServesTheTilesAsJson(JenkinsRule r) throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("shipped"));
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        JenkinsRule.WebClient client = r.createWebClient();
        Page page = client.goTo("view/wall/wallData", "application/json");
        JSONObject payload = JSONObject.fromObject(page.getWebResponse().getContentAsString());

        assertTrue(payload.getLong("generatedAt") > 0, "the browser corrects its clock from this");
        JSONArray tiles = payload.getJSONArray("tiles");
        assertEquals(1, tiles.size());
        JSONObject tile = tiles.getJSONObject(0);
        assertEquals("shipped", tile.getString("label"));
        assertEquals("shipped", tile.getString("name"));
        assertEquals("success", tile.getString("status"));
        assertEquals("job/shipped/", tile.getString("url"));
        assertFalse(tile.has("building"), "flags are omitted when false to keep the payload small");
    }

    @Test
    void configurationSurvivesARoundTripThroughTheForm(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");
        view.setPalette("colorsafe");
        view.setShape("octagon");
        view.setAnimation("stripes");
        view.setSizing("scroll");
        view.setSortBy("status");
        view.setStatusScope("problems");
        view.setPacking("grid");
        view.setRefreshSeconds(12);
        view.setMinTileHeight(140);
        view.setTileGap(10);
        view.setSeamWidth(5);
        view.setShowBuildNumber(true);
        view.setShowHeader(false);
        view.setShowFolderPath(false);
        view.setHideDisabled(true);
        view.setBurnInProtection(true);
        view.setNameReplaceRegex("^ci-");
        view.setCustomFailure("#ff0055");

        r.submit(r.createWebClient().getPage(view, "configure").getFormByName("viewConfig"));

        LiveWallView reloaded = (LiveWallView) r.jenkins.getView("wall");
        assertNotNull(reloaded);
        assertSame(Palette.COLORSAFE, reloaded.getPalette());
        assertSame(TileShape.OCTAGON, reloaded.getShape());
        assertSame(TileAnimation.STRIPES, reloaded.getAnimation());
        assertSame(Sizing.SCROLL, reloaded.getSizing());
        assertSame(SortBy.STATUS, reloaded.getSortBy());
        assertSame(StatusScope.PROBLEMS, reloaded.getStatusScope());
        assertSame(Packing.GRID, reloaded.getPacking());
        assertEquals(12, reloaded.getRefreshSeconds());
        assertEquals(140, reloaded.getMinTileHeight());
        assertEquals(10, reloaded.getTileGap());
        assertEquals(5, reloaded.getSeamWidth());
        assertTrue(reloaded.isShowBuildNumber());
        assertFalse(reloaded.isShowHeader());
        assertFalse(reloaded.isShowFolderPath());
        assertTrue(reloaded.isHideDisabled());
        assertTrue(reloaded.isBurnInProtection());
        assertEquals("^ci-", reloaded.getNameReplaceRegex());
        assertEquals("#ff0055", reloaded.getCustomFailure());
    }

    @Test
    void bothPagesRenderAndTheKioskPageCarriesNoJenkinsChrome(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("some-job");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        // Server-side rendering is what is under test here; the wall's own scripting is not.
        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);

        String embedded = client.goTo("view/wall/").getWebResponse().getContentAsString();
        assertTrue(embedded.contains("lw-root--embedded"), "the wall renders inside the view page");
        assertTrue(embedded.contains("data-palette=\"vivid\""));
        assertTrue(embedded.contains("data-packing=\"interlock\""), "the browser needs the packing mode");
        assertTrue(embedded.contains("data-gap=\"0\""));
        assertTrue(embedded.contains("data-seam=\"2\""));

        String kiosk = client.goTo("view/wall/wall").getWebResponse().getContentAsString();
        assertTrue(kiosk.contains("lw-root--kiosk"));
        assertFalse(kiosk.contains("breadcrumbBar"), "nothing but the wall on the television page");
    }

    @Test
    void theBuildNumberSettingReachesTheBrowserAsAnAttributeTheStylesheetCanSee(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("job2");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);

        String off = client.goTo("view/wall/wall").getWebResponse().getContentAsString();
        assertTrue(off.contains("data-show-build-number=\"false\""), "off by default");

        view.setShowBuildNumber(true);

        // wall.css reserves room below the label from this attribute, and wall.js sizes the badge
        // from the tile rather than from the label only when it is set. Rename it and build numbers
        // silently go back to being drawn on top of the job name.
        String on = client.goTo("view/wall/wall").getWebResponse().getContentAsString();
        assertTrue(
                on.contains("data-show-build-number=\"true\""),
                "the stylesheet needs this hook to keep the label clear of the badge");
    }

    @Test
    void theKioskPageAddressesJenkinsThroughItsOwnRootAndNotTheServerRoot(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("some-job");
        LiveWallView view = createView(r, "wall");
        view.setIncludeRegex(".*");

        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);
        String kiosk = client.goTo("view/wall/wall").getWebResponse().getContentAsString();

        // The harness serves Jenkins from a context path, which is exactly the case this page used
        // to get wrong: rendering a bare <html> left ${rootURL} undefined, so the wall polled
        // /view/wall/wallData instead of <root>/view/wall/wallData and every refresh was a 404.
        // Anyone behind a reverse proxy, or running mvn hpi:run, saw a permanently empty wall.
        assertTrue(
                kiosk.contains("data-data-url=\"" + r.contextPath + "/view/wall/wallData\""),
                "the kiosk page must poll through the Jenkins root, not the server root");
        assertTrue(kiosk.contains("data-root-url=\"" + r.contextPath + "/\""), "and link to jobs through it too");
    }

    @Test
    void savingTheLookPersistsOnlyWhatThePreviewBarOwns(JenkinsRule r) throws Exception {
        LiveWallView view = createView(r, "wall");
        view.setSizing("scroll");
        view.setStatusScope("problems");
        view.setRefreshSeconds(23);

        JenkinsRule.WebClient client = r.createWebClient();
        WebRequest post = new WebRequest(client.createCrumbedUrl("view/wall/saveLook"), HttpMethod.POST);
        post.setRequestParameters(List.of(
                new NameValuePair("palette", "neon"),
                new NameValuePair("shape", "hexagon"),
                new NameValuePair("animation", "stripes"),
                new NameValuePair("packing", "grid"),
                new NameValuePair("sortBy", "status"),
                new NameValuePair("gap", "6"),
                new NameValuePair("seam", "4")));
        assertEquals(200, client.getPage(post).getWebResponse().getStatusCode());

        assertSame(Palette.NEON, view.getPalette());
        assertSame(TileShape.HEXAGON, view.getShape());
        assertSame(TileAnimation.STRIPES, view.getAnimation());
        assertSame(Packing.GRID, view.getPacking());
        assertSame(SortBy.STATUS, view.getSortBy());
        assertEquals(6, view.getTileGap());
        assertEquals(4, view.getSeamWidth());

        // The bar cannot reach these, so saving from it must not quietly reset them to defaults.
        assertSame(Sizing.SCROLL, view.getSizing(), "saving a look must not touch sizing");
        assertSame(StatusScope.PROBLEMS, view.getStatusScope(), "nor the status scope");
        assertEquals(23, view.getRefreshSeconds(), "nor the refresh interval");

        // And it has to survive a reload, or the television will not see it.
        View reloaded = r.jenkins.getView("wall");
        assertNotNull(reloaded);
        assertSame(Palette.NEON, ((LiveWallView) reloaded).getPalette());
    }

    @Test
    void theSaveButtonCarriesAWorkingCrumb(JenkinsRule r) throws Exception {
        createView(r, "wall");

        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);
        String page = client.goTo("view/wall/").getWebResponse().getContentAsString();

        // The crumb is rendered server-side into the button because it is bound to the HTTP
        // session. If this expression ever breaks, the button silently stops working.
        assertTrue(page.contains("data-lw-action=\"save\""), "the Save button should be offered");
        assertTrue(
                page.contains("data-lw-crumb-field=\""
                        + Jenkins.get().getCrumbIssuer().getCrumbRequestField()),
                "with the crumb field name");
        assertFalse(page.contains("data-lw-crumb=\"\""), "and a crumb that is not empty");
    }

    @Test
    void savingTheLookNeedsPermissionAndAPost(JenkinsRule r) throws Exception {
        createView(r, "wall");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));

        JenkinsRule.WebClient reader = r.createWebClient().login("reader");
        reader.setThrowExceptionOnFailingStatusCode(false);

        WebRequest post = new WebRequest(reader.createCrumbedUrl("view/wall/saveLook"), HttpMethod.POST);
        post.setRequestParameters(List.of(new NameValuePair("palette", "neon")));
        assertEquals(
                403,
                reader.getPage(post).getWebResponse().getStatusCode(),
                "someone who can only read the wall cannot change it for everyone");

        // A GET must not change state either, however well-formed it looks.
        assertEquals(
                405,
                reader.goTo("view/wall/saveLook?palette=neon", null)
                        .getWebResponse()
                        .getStatusCode(),
                "saving a look is a POST");

        assertSame(Palette.VIVID, ((LiveWallView) r.jenkins.getView("wall")).getPalette());
    }

    @Test
    void theViewPageStillOffersTheOtherViewsToNavigateTo(JenkinsRule r) throws Exception {
        createView(r, "wall");
        createView(r, "elsewhere");

        JenkinsRule.WebClient client = r.createWebClient();
        client.setJavaScriptEnabled(false);
        String embedded = client.goTo("view/wall/").getWebResponse().getContentAsString();

        // Replacing View/main.jelly wholesale means the view tabs are ours to render; without them
        // the wall is a dead end you can only leave through the breadcrumbs.
        assertTrue(
                embedded.contains(r.contextPath + "/view/elsewhere/"),
                "the view page has to render the view tabs itself");
    }

    private static JobStatus statusOf(List<Tile> tiles, String label) {
        return tiles.stream()
                .filter(tile -> tile.label().equals(label))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static List<String> labelsFrom(JenkinsRule r, String url) throws Exception {
        Page page = r.createWebClient().goTo(url, "application/json");
        JSONArray tiles = JSONObject.fromObject(page.getWebResponse().getContentAsString())
                .getJSONArray("tiles");
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            labels.add(tiles.getJSONObject(i).getString("label"));
        }
        return labels;
    }

    private static List<String> labels(LiveWallView view) {
        return view.getTiles().stream().map(Tile::label).collect(Collectors.toList());
    }
}
