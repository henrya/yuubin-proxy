package com.yuubin.proxy.core.proxy.impl.http.rules;

import com.yuubin.proxy.entity.Rule;
import java.util.List;

/**
 * Utility class for finding matching proxy routing rules based on host and
 * path.
 */
public final class RuleMatcher {

    private RuleMatcher() {
        // Private constructor for utility class
    }

    /**
     * Finds a matching routing rule for the given host and path.
     * Rules with a specified host and path take precedence over host-only rules.
     * Matches the path exactly or as a prefix followed by a slash.
     *
     * <p>
     * When {@code path} is {@code null} (CONNECT tunnels), matching is based
     * solely on the host — the rule's path is ignored because CONNECT requests
     * have no HTTP path. Among host matches, host-only rules are preferred
     * over rules that also specify a path.
     * </p>
     *
     * @param rules The list of available rules.
     * @param host  The requested host.
     * @param path  The request path (may be null for CONNECT tunnels).
     * @return The matching rule, or null if no match is found.
     */
    public static Rule match(List<Rule> rules, String host, String path) {
        if (rules == null) {
            return null;
        }
        return rules.stream()
                .filter(r -> isRuleMatch(r, host, path))
                .min(RuleMatcher::compareRules) // Prefer the most specific matching rule
                .orElse(null);
    }

    private static boolean isRuleMatch(Rule r, String host, String path) {
        // Host check (if specified)
        if (r.getHost() != null && !r.getHost().isEmpty() && !r.getHost().equalsIgnoreCase(host)) {
            return false;
        }
        if (path == null && r.getPath() != null && !r.getPath().isEmpty()) {
            return false;
        }

        // CONNECT tunnels have no path — match on host alone
        if (path == null) {
            return true;
        }
        // Path check
        String rulePath = r.getPath();
        if (rulePath == null || rulePath.isEmpty()) {
            return true;
        }
        if (rulePath.equals(path)) {
            return true;
        }
        if (path.startsWith(rulePath)) {
            return rulePath.equals("/") || path.length() == rulePath.length()
                    || path.charAt(rulePath.length()) == '/';
        }
        return false;
    }

    private static int compareRules(Rule a, Rule b) {
        boolean firstHasHost = a.getHost() != null && !a.getHost().isEmpty();
        boolean secondHasHost = b.getHost() != null && !b.getHost().isEmpty();
        if (firstHasHost && !secondHasHost) {
            return -1;
        }
        if (!firstHasHost && secondHasHost) {
            return 1;
        }
        boolean firstHasPath = a.getPath() != null && !a.getPath().isEmpty();
        boolean secondHasPath = b.getPath() != null && !b.getPath().isEmpty();
        if (firstHasPath && !secondHasPath) {
            return -1;
        }
        if (!firstHasPath && secondHasPath) {
            return 1;
        }
        if (firstHasPath) {
            return Integer.compare(b.getPath().length(), a.getPath().length());
        }
        return 0;
    }
}
