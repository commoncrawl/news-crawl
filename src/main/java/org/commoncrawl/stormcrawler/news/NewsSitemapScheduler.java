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

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.SiteMapParserBolt;
import org.apache.stormcrawler.persistence.AdaptiveScheduler;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.util.ConfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AdaptiveScheduler} that additionally retires ephemeral sitemaps. Sites that mint endless
 * unique sitemap URLs (dated, counter- or hash-based) would otherwise be revisited forever; this
 * scheduler tombstones such a sitemap once it has both stopped changing and stopped yielding recent
 * news, so it is never selected again.
 *
 * <p>A {@link Status#FETCHED} sitemap is tombstoned when both conditions hold:
 *
 * <ul>
 *   <li>its content has not been modified for at least a configurable interval ({@value
 *       #STALE_INTERVAL_PARAM}, in minutes), based on the {@code signatureChangeDate} metadata
 *       written by {@link AdaptiveScheduler}; and
 *   <li>it yields no recent news, i.e. {@code numLinks == 0}, set by {@link NewsSiteMapParserBolt}.
 * </ul>
 *
 * <p>Because a sitemap that still changes keeps its signature moving, the decision is based on
 * change, not on URL shape. Tombstoning returns {@link Optional#empty()} (no next-fetch date);
 * every other case is delegated to {@link AdaptiveScheduler}.
 */
public class NewsSitemapScheduler extends AdaptiveScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(NewsSitemapScheduler.class);

    /** Minimum time (minutes) a sitemap must be unmodified before it may be tombstoned. */
    public static final String STALE_INTERVAL_PARAM = "scheduler.sitemap.ephemeral.staleInterval";

    /** Default staleness interval: 180 days (in minutes). */
    private static final int DEFAULT_STALE_INTERVAL_MINUTES = 180 * 24 * 60;

    private long staleMillis;

    @Override
    public void init(Map<String, Object> stormConf) {
        super.init(stormConf);
        int minutes =
                ConfUtils.getInt(stormConf, STALE_INTERVAL_PARAM, DEFAULT_STALE_INTERVAL_MINUTES);
        this.staleMillis = minutes * 60_000L;
        LOG.info(
                "NewsSitemapScheduler: tombstone sitemaps not modified for > {} min with numLinks=0",
                minutes);
    }

    @Override
    public Optional<Date> schedule(Status status, Metadata metadata) {
        if (isDeadEphemeralSitemap(status, metadata, staleMillis)) {
            // tombstone: no next-fetch date
            return Optional.empty();
        }
        return super.schedule(status, metadata);
    }

    /** Static and package-private so the predicate can be unit-tested without the superclass. */
    static boolean isDeadEphemeralSitemap(Status status, Metadata metadata, long staleMillis) {
        if (status != Status.FETCHED || !isSitemap(metadata)) {
            return false;
        }
        return notModifiedLongerThan(metadata, staleMillis) && noRecentNews(metadata);
    }

    private static boolean isSitemap(Metadata m) {
        return Boolean.parseBoolean(m.getFirstValue(SiteMapParserBolt.isSitemapKey))
                || Boolean.parseBoolean(m.getFirstValue(NewsSiteMapParserBolt.isSitemapNewsKey))
                || Boolean.parseBoolean(m.getFirstValue(NewsSiteMapParserBolt.isSitemapIndexKey))
                || Boolean.parseBoolean(
                        m.getFirstValue(NewsSiteMapParserBolt.isSitemapVerifiedKey));
    }

    /** {@code numLinks == 0} means the last parse found no recent news links. */
    private static boolean noRecentNews(Metadata m) {
        return "0".equals(m.getFirstValue(NewsSiteMapParserBolt.numLinksKey));
    }

    /**
     * True when the sitemap's content has not been modified for longer than {@code millis}, based
     * on the {@code signatureChangeDate} metadata set by {@link AdaptiveScheduler}.
     */
    private static boolean notModifiedLongerThan(Metadata m, long millis) {
        String changed = m.getFirstValue(AdaptiveScheduler.SIGNATURE_MODIFIED_KEY);
        if (changed == null) {
            return false; // no change history yet -> never tombstone (safe default)
        }
        try {
            long changedAt = Instant.parse(changed).toEpochMilli();
            return (System.currentTimeMillis() - changedAt) > millis;
        } catch (Exception e) {
            return false; // unparseable date -> treat as not stale (never tombstone)
        }
    }
}
