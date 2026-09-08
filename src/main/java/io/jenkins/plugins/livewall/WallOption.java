package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.ListBoxModel;

/**
 * Common contract for the small enums that make up a wall's look: each constant has a stable id
 * that survives config round-trips (and shows up in CSS as a {@code data-} attribute) plus a human
 * label for the drop-downs.
 */
public interface WallOption {

    /** Stable, lowercase, kebab-case id. Persisted in config.xml and emitted into the markup. */
    @NonNull
    String getId();

    /** Label shown in the view configuration form, where there is room to explain the choice. */
    @NonNull
    String getDisplayName();

    /**
     * The label without its explanation, for the preview bar, where seven drop-downs share a row
     * and a full sentence in each one is unreadable. Everything before the em dash, if there is one.
     */
    @NonNull
    default String getShortName() {
        String name = getDisplayName();
        int dash = name.indexOf(" — ");
        return dash > 0 ? name.substring(0, dash) : name;
    }

    /**
     * Resolves an id back to a constant, falling back to {@code fallback} for anything unknown so
     * that a hand-edited config.xml can never break the wall.
     */
    static <E extends Enum<E> & WallOption> E parse(Class<E> type, @CheckForNull String id, E fallback) {
        if (id != null) {
            String trimmed = id.trim();
            for (E candidate : type.getEnumConstants()) {
                if (candidate.getId().equalsIgnoreCase(trimmed)
                        || candidate.name().equalsIgnoreCase(trimmed)) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    /** Builds the drop-down model for a {@code <f:select>} over all constants of an option enum. */
    static <E extends Enum<E> & WallOption> ListBoxModel items(Class<E> type) {
        ListBoxModel model = new ListBoxModel();
        for (E candidate : type.getEnumConstants()) {
            model.add(candidate.getDisplayName(), candidate.getId());
        }
        return model;
    }
}
