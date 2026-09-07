package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;
import net.sf.json.JSONObject;

/**
 * One job, reduced to everything the wall needs and nothing else.
 *
 * <p>Timing is sent raw rather than pre-rendered: the browser interpolates the progress of a
 * running build between polls, so a five second refresh still produces a smoothly advancing bar.
 *
 * @param label the text drawn on the tile, after any name rewriting
 * @param fullName the job's full name, used for the tooltip and for diffing between refreshes
 * @param url the job's URL relative to the Jenkins root
 * @param status last known outcome, independent of whether a build is running
 * @param building whether a build is in progress
 * @param queued whether the job is waiting in the build queue
 * @param buildNumber number of the most recent build, or 0 if the job has never built
 * @param startedAt epoch millis at which the running build started, or 0 when not building
 * @param estimatedDuration expected duration of the running build in millis, or -1 when unknown
 * @param completedAt epoch millis of the most recent completed build, or 0 if there is none
 */
public record Tile(
        @NonNull String label,
        @NonNull String fullName,
        @NonNull String url,
        @NonNull JobStatus status,
        boolean building,
        boolean queued,
        int buildNumber,
        long startedAt,
        long estimatedDuration,
        long completedAt) {

    /** Serialises to the compact shape consumed by {@code live-wall.js}. */
    @NonNull
    JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.element("label", label);
        json.element("name", fullName);
        json.element("url", url);
        json.element("status", status.getId());
        if (building) {
            json.element("building", true);
            json.element("startedAt", startedAt);
            json.element("estimatedDuration", estimatedDuration);
        }
        if (queued) {
            json.element("queued", true);
        }
        if (buildNumber > 0) {
            json.element("buildNumber", buildNumber);
        }
        return json;
    }
}
