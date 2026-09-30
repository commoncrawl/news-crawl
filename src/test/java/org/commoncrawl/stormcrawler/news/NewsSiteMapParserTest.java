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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.sitemaps.UnknownFormatException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.Outlink;
import org.apache.stormcrawler.parse.ParsingTester;
import org.apache.stormcrawler.protocol.HttpRobotRulesParser;
import org.apache.stormcrawler.protocol.RobotRulesParser;
import org.apache.stormcrawler.util.MetadataTransfer;
import org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt.SitemapCrossCheckResult;
import org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt.SitemapType;
import org.junit.Before;
import org.junit.Test;

public class NewsSiteMapParserTest extends ParsingTester {

    @Before
    public void setupParserBolt() {
        setupParserBolt(new NewsSiteMapParserBolt());
        Map<String, Object> config = new HashMap<>();
        config.put("sitemap.sniffContent", true);
        // allow items published during the last week
        config.put("sitemap.filter.hours.since.modified", 168);
        config.put("http.agent.name", "UnitTestBot");
        prepareParserBolt("test.parsefilters.json", config);
    }

    /**
     * A robots.txt parser whose cache holds nothing: every lookup returns {@link
     * RobotRulesParser#EMPTY_RULES}, which the bolt reports as DENIED_NO_ROBOTS_CACHED.
     */
    private HttpRobotRulesParser emptyRobotsCache() {
        HttpRobotRulesParser robots = mock(HttpRobotRulesParser.class);
        when(robots.getRobotRulesSetFromCache(any(URL.class)))
                .thenReturn(RobotRulesParser.EMPTY_RULES);
        return robots;
    }

    /**
     * Puts a robots.txt for {@code host} into the mocked cache, declaring the given sitemaps.
     *
     * <p>Matching is by host rather than by URL equality on purpose: {@link URL#equals(Object)}
     * resolves host names via DNS.
     */
    private void withCachedRobots(HttpRobotRulesParser robots, String host, String... sitemaps) {
        BaseRobotRules rules = mock(BaseRobotRules.class);
        when(rules.getSitemaps()).thenReturn(Arrays.asList(sitemaps));
        when(robots.getRobotRulesSetFromCache(
                        argThat(url -> url != null && host.equals(url.getHost()))))
                .thenReturn(rules);
    }

    /** A robots.txt parser returning the same cached rules for every host. */
    private HttpRobotRulesParser robotsForAllHosts(String... sitemaps) {
        HttpRobotRulesParser robots = mock(HttpRobotRulesParser.class);
        BaseRobotRules rules = mock(BaseRobotRules.class);
        when(rules.getSitemaps()).thenReturn(Arrays.asList(sitemaps));
        when(robots.getRobotRulesSetFromCache(any(URL.class))).thenReturn(rules);
        return robots;
    }

    @Test
    public void testSiteMapParser() throws IOException, UnknownFormatException {
        String url = "https://example.org/sitemap-news.xml";
        byte[] content = readContent("sitemap-news.xml");
        String contentType = "";
        Metadata parentMetadata = new Metadata();
        List<Outlink> links = new ArrayList<>();

        SitemapType type = ((NewsSiteMapParserBolt) bolt).detectContent(url, content);
        assertEquals(SitemapType.NEWS, type);

        ((NewsSiteMapParserBolt) bolt)
                .parseSiteMap(url, content, contentType, parentMetadata, links);

        // unmodified sitemap:
        // - publication date is far in the past, link should be skipped
        // <news:publication_date>2008-12-23</news:publication_date>
        assertEquals("Outdated link not skipped", 0, links.size());

        // now set the publication date to yesterday
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        content =
                (new String(content, StandardCharsets.UTF_8))
                        .replace(
                                "<news:publication_date>2008-12-23</news:publication_date>",
                                "<news:publication_date>"
                                        + yesterday.format(
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                        + "</news:publication_date>")
                        .getBytes(StandardCharsets.UTF_8);
        ((NewsSiteMapParserBolt) bolt)
                .parseSiteMap(url, content, contentType, parentMetadata, links);

        assertEquals(
                "Expected one <loc> and one additional <xhtml:link> link - image links are ignored",
                2,
                links.size());
    }

    protected byte[] readContent(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(getClass().getClassLoader().getResourceAsStream(filename), baos);
        return baos.toByteArray();
    }

    @Test
    public void testFeedWithSitemapNamespace() throws IOException, UnknownFormatException {
        String url = "https://example.org/feed.xml";
        byte[] content = readContent("feed-with-sitemap-namespace.xml");
        SitemapType type = ((NewsSiteMapParserBolt) bolt).detectContent(url, content);
        assertNotEquals(
                "RSS feed with sitemap namespace should not be detected as sitemap",
                SitemapType.NEWS,
                type);
        assertNotEquals(
                "RSS feed with sitemap namespace should not be detected as sitemap",
                SitemapType.SITEMAP,
                type);
    }

    @Test
    public void testCrossHostSitemapVerification()
            throws IOException, UnknownFormatException, URISyntaxException {
        String sitemapURL = "https://www.example.org/sitemap-news.xml";
        String adSitemapURL = "https://www.example.net/sitemap-ads.xml";

        // The cached robots.txt of any cross-host target references a different sitemap, so
        // cross-host outlinks are rejected. The same-host outlink (article55 on www.example.org)
        // never triggers a robots.txt lookup.
        ((NewsSiteMapParserBolt) bolt).setRobotRulesParser(robotsForAllHosts(adSitemapURL));

        // Set up test data
        byte[] content = readContent("cross-sitemap-news.xml");
        String contentType = "";
        Metadata parentMetadata = new Metadata();
        List<Outlink> links = new ArrayList<>();

        // Set recent publication date and cross-host URL
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        content =
                (new String(content, StandardCharsets.UTF_8))
                        .replace(
                                "<news:publication_date>2008-12-23</news:publication_date>",
                                "<news:publication_date>"
                                        + yesterday.format(
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                        + "</news:publication_date>")
                        .getBytes(StandardCharsets.UTF_8);

        ((NewsSiteMapParserBolt) bolt)
                .parseSiteMap(sitemapURL, content, contentType, parentMetadata, links);
        // same-host outlink allowed, both cross-host outlinks rejected
        assertEquals(3, links.size());
        assertThat(
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(0), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
        assertThat(
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(1), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.DENIED_NOT_DECLARED));
        assertThat(
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(2), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.DENIED_NOT_DECLARED));
    }

    /**
     * Tests cross-host sitemap submissions with the following structure:
     *
     * <pre>
     * www.example.org/sitemap-news.xml
     *    └── www.example.com/sports/news1.html
     *    └── www.example.org/business/article55.html
     *    └── www.example.net/ads/sponsored-content.html
     *
     * www.example.org/robots.txt
     *   └── www.example.org/sitemap-index.xml
     *       └── www.example.org/sitemap-news.xml
     *
     * www.example.com/robots.txt
     *   └── www.example.org/sitemap-index.xml (shared with www.example.org)
     *
     * www.example.net/robots.txt
     *   └── www.example.net/sitemap.xml
     *       └── www.example.net/ads/sponsored-content.html
     * </pre>
     *
     * URLs from example.org and example.com pass crossSubmitCheck since their robots.txt reference
     * the same sitemap index which contains the sitemap from which the link is fetched. URLs from
     * example.net fail since their robots reference a different sitemap index.
     */
    @Test
    public void testCrossHostSubmissionSitemapsShouldRejectExampleNet()
            throws IOException, UnknownFormatException, URISyntaxException {
        String sitemapURL = "https://www.example.org/sitemap-news.xml";
        String sitemapIndexURL = "https://www.example.org/sitemap-index.xml";
        String adSitemapURL = "https://www.example.net/sitemap-ads.xml";

        // Cached robots.txt per target host: example.org and example.com declare the sitemap
        // index the sitemap was reached through, example.net declares only its own ad sitemap.
        HttpRobotRulesParser robots = emptyRobotsCache();
        withCachedRobots(robots, "www.example.org", sitemapIndexURL);
        withCachedRobots(robots, "www.example.com", sitemapIndexURL);
        withCachedRobots(robots, "www.example.net", adSitemapURL);
        ((NewsSiteMapParserBolt) bolt).setRobotRulesParser(robots);

        // Set up test data
        byte[] content = readContent("cross-sitemap-news.xml");
        String contentType = "";
        // the sitemap's own metadata carries its discovery trail (url.path), recorded by
        // metadata.track.path and persisted in the status index via metadata.persist. The trail
        // holds the sitemap's ancestors only - MetadataTransfer appends the *source* URL of each
        // hop, so a sitemap never appears in its own url.path.
        Metadata parentMetadata = new Metadata();
        parentMetadata.addValues(
                MetadataTransfer.urlPathKeyName, Collections.singletonList(sitemapIndexURL));
        List<Outlink> links = new ArrayList<>();

        // Set recent publication date and cross-host URL
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        content =
                (new String(content, StandardCharsets.UTF_8))
                        .replace(
                                "<news:publication_date>2008-12-23</news:publication_date>",
                                "<news:publication_date>"
                                        + yesterday.format(
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                        + "</news:publication_date>")
                        .getBytes(StandardCharsets.UTF_8);

        ((NewsSiteMapParserBolt) bolt)
                .parseSiteMap(sitemapURL, content, contentType, parentMetadata, links);
        // Verify the cross-host link is allowed and included
        assertEquals(3, links.size());
        assertThat(
                "example.org is the sitemap's own host",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(0), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
        assertThat(
                "example.net's robots.txt does not declare the sitemap nor its index",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(1), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.DENIED_NOT_DECLARED));
        assertThat(
                "example.com's robots.txt declares the index the sitemap was reached through",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(2), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
    }

    @Test
    public void testCrossHostSubmissionSitemapsShouldAcceptExampleNet()
            throws IOException, UnknownFormatException, URISyntaxException {
        String sitemapURL = "https://www.example.org/sitemap-news.xml";
        String sitemapIndexURL = "https://www.example.org/sitemap-index.xml";
        String adSitemapURL = "https://www.example.net/sitemap-ads.xml";

        // Identical to testCrossHostSubmissionSitemapsShouldRejectExampleNet except for one
        // stub: example.net's own robots.txt now also declares the example.org sitemap index.
        // Nothing about example.org changes - the vouching comes from the target host.
        HttpRobotRulesParser robots = emptyRobotsCache();
        withCachedRobots(robots, "www.example.org", sitemapIndexURL);
        withCachedRobots(robots, "www.example.com", sitemapIndexURL);
        withCachedRobots(robots, "www.example.net", sitemapIndexURL, adSitemapURL);
        ((NewsSiteMapParserBolt) bolt).setRobotRulesParser(robots);

        // Set up test data
        byte[] content = readContent("cross-sitemap-news.xml");
        String contentType = "";
        // the sitemap's own metadata carries its discovery trail (url.path), recorded by
        // metadata.track.path and persisted in the status index via metadata.persist
        Metadata parentMetadata = new Metadata();
        parentMetadata.addValues(
                MetadataTransfer.urlPathKeyName, Collections.singletonList(sitemapIndexURL));
        List<Outlink> links = new ArrayList<>();

        // Set recent publication date and cross-host URL
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        content =
                (new String(content, StandardCharsets.UTF_8))
                        .replace(
                                "<news:publication_date>2008-12-23</news:publication_date>",
                                "<news:publication_date>"
                                        + yesterday.format(
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                        + "</news:publication_date>")
                        .getBytes(StandardCharsets.UTF_8);

        ((NewsSiteMapParserBolt) bolt)
                .parseSiteMap(sitemapURL, content, contentType, parentMetadata, links);
        // Verify the cross-host link is allowed and included
        assertEquals(3, links.size());
        assertThat(
                "example.org is the sitemap's own host",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(0), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
        assertThat(
                "example.net's robots.txt now declares the index the sitemap was reached through",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(1), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
        assertThat(
                "example.com's robots.txt declares the index the sitemap was reached through",
                ((NewsSiteMapParserBolt) bolt)
                        .crossSubmitCheck(links.get(2), sitemapURL, parentMetadata),
                is(SitemapCrossCheckResult.ALLOWED));
    }
}
