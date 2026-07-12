/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commoncrawl.stormcrawler.news;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.SiteMapParserBolt;
import org.apache.stormcrawler.persistence.AdaptiveScheduler;
import org.apache.stormcrawler.persistence.Status;
import org.junit.Test;

/**
 * Tests the tombstone predicate of {@link NewsSitemapScheduler}: a fetched sitemap is retired only
 * when it has not been modified long enough <em>and</em> yields no recent news. Every other case
 * falls through to the normal adaptive schedule.
 */
public class NewsSitemapSchedulerTest {

    private static final long STALE_30D = 30L * 24 * 3600 * 1000;

    private static Metadata sitemap(String numLinks, String signatureChangeDate) {
        Metadata m = new Metadata();
        m.setValue(SiteMapParserBolt.isSitemapKey, "true");
        if (numLinks != null) {
            m.setValue(NewsSiteMapParserBolt.numLinksKey, numLinks);
        }
        if (signatureChangeDate != null) {
            m.setValue(AdaptiveScheduler.SIGNATURE_MODIFIED_KEY, signatureChangeDate);
        }
        return m;
    }

    private static String daysAgo(int days) {
        return Instant.ofEpochMilli(System.currentTimeMillis() - days * 24L * 3600 * 1000)
                .toString();
    }

    private static boolean dead(Status status, Metadata m) {
        return NewsSitemapScheduler.isDeadEphemeralSitemap(status, m, STALE_30D);
    }

    /** Unmodified long enough + no recent news => tombstone. */
    @Test
    public void staleAndNoNews_isTombstoned() {
        assertTrue(dead(Status.FETCHED, sitemap("0", daysAgo(200))));
    }

    /** Still producing news => kept, even if unmodified for a long time. */
    @Test
    public void hasRecentNews_notTombstoned() {
        assertFalse(dead(Status.FETCHED, sitemap("5", daysAgo(200))));
    }

    /** Modified recently => not stale => kept. */
    @Test
    public void recentlyModified_notTombstoned() {
        assertFalse(dead(Status.FETCHED, sitemap("0", daysAgo(1))));
    }

    /** No change history yet => never tombstone (safe default). */
    @Test
    public void noChangeHistory_notTombstoned() {
        assertFalse(dead(Status.FETCHED, sitemap("0", null)));
    }

    /** Non-sitemap docs are out of scope. */
    @Test
    public void nonSitemap_notTombstoned() {
        Metadata m = new Metadata();
        m.setValue(NewsSiteMapParserBolt.numLinksKey, "0");
        m.setValue(AdaptiveScheduler.SIGNATURE_MODIFIED_KEY, daysAgo(200));
        assertFalse(dead(Status.FETCHED, m));
    }

    /** Only successful re-fetches are considered. */
    @Test
    public void notFetched_notTombstoned() {
        assertFalse(dead(Status.DISCOVERED, sitemap("0", daysAgo(200))));
    }
}
