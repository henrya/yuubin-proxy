package com.yuubin.proxy.core.proxy.impl.http;

import com.yuubin.proxy.core.proxy.impl.http.rules.RuleMatcher;
import com.yuubin.proxy.entity.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMatcherTest {

    @Test
    void match_withEmptyRules_returnsNull() {
        assertThat(RuleMatcher.match(null, "localhost", "/")).isNull();
        assertThat(RuleMatcher.match(List.of(), "localhost", "/")).isNull();
    }

    @Test
    void match_hostOnly_matchesCorrectly() {
        Rule r1 = new Rule();
        r1.setHost("example.com");

        Rule r2 = new Rule();
        r2.setHost("other.com");

        List<Rule> rules = List.of(r1, r2);

        assertThat(RuleMatcher.match(rules, "example.com", "/path")).isEqualTo(r1);
        assertThat(RuleMatcher.match(rules, "other.com", "/path")).isEqualTo(r2);
        assertThat(RuleMatcher.match(rules, "nomatch.com", "/path")).isNull();
    }

    @Test
    void match_pathOnly_matchesPrefix() {
        Rule r1 = new Rule();
        r1.setPath("/api");

        Rule r2 = new Rule();
        r2.setPath("/api/v1");

        List<Rule> rules = List.of(r1, r2);

        // /api/v1 goes to r2 (most specific path)
        assertThat(RuleMatcher.match(rules, "localhost", "/api/v1/users")).isEqualTo(r2);

        // /api/v2 goes to r1
        assertThat(RuleMatcher.match(rules, "localhost", "/api/v2/users")).isEqualTo(r1);

        // Exact match
        assertThat(RuleMatcher.match(rules, "localhost", "/api")).isEqualTo(r1);
    }

    @Test
    void match_pathPrefixMustMatchDirectory() {
        Rule rule = new Rule();
        rule.setPath("/app");

        List<Rule> rules = List.of(rule);

        // Does not match /apple (not a subpath!)
        assertThat(RuleMatcher.match(rules, "localhost", "/apple")).isNull();

        // Matches /app and /app/something
        assertThat(RuleMatcher.match(rules, "localhost", "/app")).isEqualTo(rule);
        assertThat(RuleMatcher.match(rules, "localhost", "/app/users")).isEqualTo(rule);
    }

    @Test
    void match_connectTunnel_ignoresPath() {
        Rule hostRule = new Rule();
        hostRule.setHost("secure.com");

        Rule hostAndPathRule = new Rule();
        hostAndPathRule.setHost("secure.com");
        hostAndPathRule.setPath("/extra");

        List<Rule> rules = List.of(hostRule, hostAndPathRule);

        // Submitting null path indicates CONNECT tunnel.
        // hostRule takes precedence because the comparison favors host-only when paths
        // are ignored.
        assertThat(RuleMatcher.match(rules, "secure.com", null)).isEqualTo(hostRule);
    }
}
