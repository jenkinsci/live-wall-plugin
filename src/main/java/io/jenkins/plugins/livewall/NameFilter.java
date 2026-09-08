package io.jenkins.plugins.livewall;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chooses which jobs make it onto the wall, by name, without anybody having to write a regular
 * expression.
 *
 * <p>A pattern is either a plain word or a wildcard:
 *
 * <ul>
 *   <li><b>No wildcard</b> — matches any name <em>containing</em> that text, ignoring case.
 *       {@code drools} finds {@code drools}, {@code drools-nightly} and {@code ci-drools}.
 *   <li><b>{@code *} and {@code ?}</b> — the whole name has to match. {@code ci-*} is everything
 *       starting with {@code ci-}; {@code *-pipeline} everything ending with it; {@code release-?}
 *       matches {@code release-1} but not {@code release-10}.
 * </ul>
 *
 * <p>Plain text meaning "contains" is the concession that makes this usable: typing a word into a
 * filter box and getting nothing back because it was not anchored is the exact frustration this
 * exists to avoid. Anyone who wants precision has the wildcards.
 *
 * <p>Patterns are separated by newlines or commas, and a job is kept if it matches <em>any</em>
 * include and <em>no</em> exclude. An empty include list keeps everything, so an empty filter is
 * not an empty wall.
 */
final class NameFilter {

    /** Keeps every job. Used whenever neither field is filled in. */
    static final NameFilter ALL = new NameFilter(List.of(), List.of());

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    private NameFilter(List<Pattern> includes, List<Pattern> excludes) {
        this.includes = includes;
        this.excludes = excludes;
    }

    @NonNull
    static NameFilter of(@CheckForNull String include, @CheckForNull String exclude) {
        List<Pattern> includes = compile(include);
        List<Pattern> excludes = compile(exclude);
        return includes.isEmpty() && excludes.isEmpty() ? ALL : new NameFilter(includes, excludes);
    }

    /** True when nothing was configured, so callers can skip the work entirely. */
    boolean isEmpty() {
        return includes.isEmpty() && excludes.isEmpty();
    }

    /**
     * Whether a job belongs on the wall.
     *
     * @param names the names to try: typically the job's full name and the label shown on the tile,
     *     so a pattern works whichever of the two someone had in mind.
     */
    boolean accepts(@NonNull String... names) {
        for (Pattern exclude : excludes) {
            if (matchesAny(exclude, names)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (Pattern include : includes) {
            if (matchesAny(include, names)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(Pattern pattern, String[] names) {
        for (String name : names) {
            if (name != null && pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Splits a field into patterns and turns each one into something that can be matched. */
    @NonNull
    private static List<Pattern> compile(@CheckForNull String field) {
        List<Pattern> patterns = new ArrayList<>();
        if (field == null) {
            return patterns;
        }
        for (String raw : field.split("[,\\r\\n]")) {
            String pattern = raw.trim();
            if (!pattern.isEmpty()) {
                patterns.add(toPattern(pattern));
            }
        }
        return patterns;
    }

    /**
     * Builds the matcher for one pattern. Everything the user typed is quoted, so the only
     * characters with any meaning are {@code *} and {@code ?} — a stray {@code .} or {@code (} is
     * just itself, which is the whole point of not using regular expressions here.
     */
    @NonNull
    private static Pattern toPattern(@NonNull String pattern) {
        boolean wildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
        StringBuilder regex = new StringBuilder();
        if (!wildcard) {
            regex.append(".*"); // a plain word means "contains"
        }
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                appendLiteral(regex, literal);
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        appendLiteral(regex, literal);
        if (!wildcard) {
            regex.append(".*");
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static void appendLiteral(StringBuilder regex, StringBuilder literal) {
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    /** Describes what a filter field will do, for the inline validation in the config form. */
    @NonNull
    static String describe(@CheckForNull String field) {
        List<String> parts = new ArrayList<>();
        if (field != null) {
            for (String raw : field.split("[,\\r\\n]")) {
                String pattern = raw.trim();
                if (pattern.isEmpty()) {
                    continue;
                }
                boolean wildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
                parts.add(
                        wildcard
                                ? "names matching " + pattern
                                : "names containing " + pattern.toLowerCase(Locale.ROOT));
            }
        }
        return String.join(", or ", parts);
    }
}
