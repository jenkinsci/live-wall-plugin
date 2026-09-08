package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The silhouette of a tile.
 *
 * <p>Shapes are cut with CSS {@code clip-path} in {@code live-wall.css}; this enum only carries the
 * id that selects them. Shapes that eat into the middle of the tile (cross, diamond) leave less
 * room for the label, which the layout code compensates for with a per-shape text inset.
 */
public enum TileShape implements WallOption {
    RECTANGLE("rectangle", "Rectangle"),
    ROUNDED("rounded", "Rounded rectangle (default)"),
    SQUARE("square", "Square"),
    CIRCLE("circle", "Circle"),
    OCTAGON("octagon", "Octagon"),
    HEXAGON("hexagon", "Hexagon"),
    DIAMOND("diamond", "Diamond"),
    PARALLELOGRAM("parallelogram", "Parallelogram"),
    CHEVRON("chevron", "Chevron"),
    CROSS("cross", "Cross");

    private final String id;
    private final String displayName;

    TileShape(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return displayName;
    }
}
