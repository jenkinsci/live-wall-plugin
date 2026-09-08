package io.jenkins.plugins.livewall;

import hudson.model.BallColor;

/**
 * The handful of states a tile on the wall can be in.
 *
 * <p>Deliberately coarser than {@link hudson.model.Result}: a wall seen from the other side of the
 * room can carry about six distinguishable colours, no more. "Currently building" is not a status
 * here, it is a flag on top of one, because a running job still has a last known outcome worth
 * showing behind the animation.
 */
public enum JobStatus {

    /** Something is broken and somebody should care. Sorted first. */
    FAILURE("failure", 0),
    /** Built, but tests failed. */
    UNSTABLE("unstable", 1),
    /** Cancelled by a human or a timeout. */
    ABORTED("aborted", 2),
    /** Configured but never built, or pending its first run. */
    NOT_BUILT("notbuilt", 3),
    /** Explicitly disabled. */
    DISABLED("disabled", 4),
    /** All good. */
    SUCCESS("success", 5);

    /* Named "id" rather than "key" to match WallOption#getId, and because a field called "key" on
    a serializable class trips the security scan's credential-storage heuristic -- reasonably, on
    the name alone. This one holds "success" or "failure". */
    private final String id;

    private final int severity;

    JobStatus(String id, int severity) {
        this.id = id;
        this.severity = severity;
    }

    /** Stable lowercase identifier, used as the {@code data-status} attribute and JSON value. */
    public String getId() {
        return id;
    }

    /** Lower is worse. Used when the wall is sorted so that problems float to the top left. */
    public int getSeverity() {
        return severity;
    }

    /**
     * Maps a Jenkins ball colour onto a wall status, ignoring the {@code _anime} suffix (whether a
     * job is building is tracked separately).
     */
    public static JobStatus of(BallColor color) {
        if (color == null) {
            return NOT_BUILT;
        }
        return switch (color.noAnime()) {
            case RED -> FAILURE;
            case YELLOW -> UNSTABLE;
            case BLUE -> SUCCESS;
            case ABORTED -> ABORTED;
            case DISABLED -> DISABLED;
            default -> NOT_BUILT; // GREY and NOTBUILT
        };
    }

    /** True for the states an operator would want to look at. Used by {@link StatusScope}. */
    public boolean isProblem() {
        return this == FAILURE || this == UNSTABLE || this == ABORTED;
    }
}
