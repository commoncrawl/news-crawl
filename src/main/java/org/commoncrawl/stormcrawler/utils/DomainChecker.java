package org.commoncrawl.stormcrawler.utils;

import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;

public class DomainChecker {

    public static String getPayLevelDomain(String domain) {
        PublicSuffixMatcher matcher = PublicSuffixMatcherLoader.getDefault();
        return matcher.getDomainRoot(domain); // Returns PLD (registered domain)
    }
}