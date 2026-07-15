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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import crawlercommons.robots.BaseRobotRules;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.TestUtil;
import org.apache.stormcrawler.parse.Outlink;
import org.apache.stormcrawler.parse.ParsingTester;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.protocol.Protocol;
import org.apache.stormcrawler.protocol.ProtocolFactory;
import org.apache.stormcrawler.util.MetadataTransfer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the cross-submit check of {@link NewsSiteMapParserBolt}: sitemaps may only submit URLs
 * of a different host if the target host's robots.txt points (directly or via the sitemap index
 * trail) to the submitting sitemap.
 */
public class CrossSubmitCheckTest extends ParsingTester {

    private static final String SITEMAP_URL = "https://www.example.org/sitemap-news.xml";
    private static final String ARTICLE_ORG = "http://www.example.org/business/article55.html";
    private static final String ARTICLE_NET = "http://www.example.net/ads/sponsored-content.html";
    private static final String ARTICLE_COM = "http://www.example.com/sports/news1.html";

    @Before
    public void setupParserBolt() {
        setupParserBolt(new NewsSiteMapParserBolt());
        prepareParserBolt("test.parsefilters.json", baseConfig());
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("sitemap.sniffContent", true);
        // allow items published during the last week
        config.put("sitemap.filter.hours.since.modified", 168);
        config.put("http.agent.name", "UnitTestBot");
        return config;
    }

    private NewsSiteMapParserBolt newsBolt() {
        return (NewsSiteMapParserBolt) bolt;
    }

    protected byte[] readContent(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(getClass().getClassLoader().getResourceAsStream(filename), baos);
        return baos.toByteArray();
    }

    private static String yesterday() {
        return LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /**
     * Reads a sitemap test resource and replaces the outdated publication date by yesterday's date
     * so that links are not skipped because of sitemap.filter.hours.since.modified.
     */
    private String readRecentContent(String filename) throws IOException {
        return new String(readContent(filename), StandardCharsets.UTF_8)
                .replace(
                        "<news:publication_date>2008-12-23</news:publication_date>",
                        "<news:publication_date>" + yesterday() + "</news:publication_date>");
    }

    private List<List<Object>> statusEmissions() {
        return output.getEmitted(Constants.StatusStreamName);
    }

    /** All tuples emitted on any stream used by the bolt (default and status stream). */
    private List<List<Object>> allEmissions() {
        List<List<Object>> all = new ArrayList<>();
        all.addAll(output.getEmitted());
        all.addAll(output.getEmitted(Constants.StatusStreamName));
        return all;
    }

    private long countStatusEmissions(String url, Status status) {
        return statusEmissions().stream()
                .filter(values -> url.equals(values.get(0)) && status.equals(values.get(2)))
                .count();
    }

    private void assertNotEmittedAtAll(String url) {
        for (List<Object> values : allEmissions()) {
            assertFalse(
                    "URL " + url + " must not appear in any emitted tuple: " + values,
                    values.contains(url));
        }
    }

    private void executeWithContent(String content) {
        Metadata metadata = new Metadata();
        Tuple tuple = TestUtil.getMockedTestTuple(SITEMAP_URL, content, metadata);
        bolt.execute(tuple);
    }

    /**
     * Outlinks failing the cross-submit check must be silently skipped: no tuple at all is emitted
     * for them (neither DISCOVERED nor ERROR), while same-host outlinks are emitted DISCOVERED and
     * the sitemap itself is marked FETCHED.
     */
    @Test
    public void testRejectedOutlinkNotEmitted() throws IOException {
        ProtocolFactory protocolFactory = mock(ProtocolFactory.class);
        Protocol protocol = mock(Protocol.class);
        when(protocolFactory.getProtocol(any(URL.class))).thenReturn(protocol);

        // robots.txt of both cross-host targets do not reference the sitemap
        BaseRobotRules rulesNet = mock(BaseRobotRules.class);
        when(protocol.getRobotRules(ARTICLE_NET)).thenReturn(rulesNet);
        when(rulesNet.getSitemaps()).thenReturn(Collections.emptyList());

        BaseRobotRules rulesCom = mock(BaseRobotRules.class);
        when(protocol.getRobotRules(ARTICLE_COM)).thenReturn(rulesCom);
        when(rulesCom.getSitemaps()).thenReturn(Collections.emptyList());

        newsBolt().setProtocolFactory(protocolFactory);

        executeWithContent(readRecentContent("cross-sitemap-news.xml"));

        // rejected cross-host outlinks appear in no emitted tuple at all
        assertNotEmittedAtAll(ARTICLE_NET);
        assertNotEmittedAtAll(ARTICLE_COM);

        // the same-host outlink is emitted exactly once as DISCOVERED
        assertEquals(1, countStatusEmissions(ARTICLE_ORG, Status.DISCOVERED));
        assertEquals(0, countStatusEmissions(ARTICLE_ORG, Status.ERROR));

        // the sitemap URL itself is marked as successfully fetched
        assertEquals(1, countStatusEmissions(SITEMAP_URL, Status.FETCHED));
    }

    /**
     * An outlink whose URL cannot be parsed as URI (space in the path) must be skipped without any
     * emission and without breaking the processing of the remaining outlinks.
     */
    @Test
    public void testMalformedOutlinkSkippedSilently() {
        String goodURL = "http://www.example.org/business/article55.html";
        String badURL = "http://www.example.org/bad path/article.html";
        String secondGoodURL = "http://www.example.org/sports/article56.html";

        String content =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n"
                        + "  xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\">\n"
                        + newsSitemapEntry(goodURL)
                        + newsSitemapEntry(badURL)
                        + newsSitemapEntry(secondGoodURL)
                        + "</urlset>";

        // all outlinks are on the sitemap's own host, the protocol factory must stay untouched
        ProtocolFactory protocolFactory = mock(ProtocolFactory.class);
        newsBolt().setProtocolFactory(protocolFactory);

        executeWithContent(content);

        // the malformed URL is skipped silently
        assertNotEmittedAtAll(badURL);

        // the remaining outlinks are still processed
        assertEquals(1, countStatusEmissions(goodURL, Status.DISCOVERED));
        assertEquals(1, countStatusEmissions(secondGoodURL, Status.DISCOVERED));
        assertEquals(1, countStatusEmissions(SITEMAP_URL, Status.FETCHED));

        verifyNoInteractions(protocolFactory);
    }

    private static String newsSitemapEntry(String loc) {
        return "<url><loc>"
                + loc
                + "</loc><news:news><news:publication>"
                + "<news:name>The Example Times</news:name><news:language>en</news:language>"
                + "</news:publication><news:publication_date>"
                + yesterday()
                + "</news:publication_date>"
                + "<news:title>Title</news:title></news:news></url>\n";
    }

    /**
     * crossSubmitCheck must not throw when the target URL has no host (mailto:, file:) or when the
     * host's registered domain is unknown to the EffectiveTldFinder — it should return false.
     *
     * <p>Currently the code throws a NullPointerException (targetHost is null), see item B4.
     */
    @Test
    public void testNullHostDoesNotThrow() throws URISyntaxException, MalformedURLException {
        // URI without a host part
        Outlink mailto = new Outlink("mailto:contact@example.org");
        assertFalse(
                "URL without host must not be allowed",
                newsBolt().crossSubmitCheck(mailto, SITEMAP_URL, new Metadata()));

        Outlink file = new Outlink("file:///tmp/x.html");
        assertFalse(
                "URL without host must not be allowed",
                newsBolt().crossSubmitCheck(file, SITEMAP_URL, new Metadata()));

        // host whose TLD is unknown to EffectiveTldFinder (lenient mode returns null)
        Outlink unknownTld = new Outlink("http://host.invalidtld123/");
        assertFalse(
                "URL with unknown TLD must not be allowed",
                newsBolt().crossSubmitCheck(unknownTld, SITEMAP_URL, new Metadata()));
    }

    /**
     * With crossSubmit.lenient=true (default) hosts are compared by their registered domain:
     * news.example.org and www.example.org are considered the same, no robots.txt lookup happens.
     * With crossSubmit.lenient=false hosts are compared literally and the check falls through to
     * the robots.txt rules.
     */
    @Test
    public void testLenientVsStrictHostComparison()
            throws URISyntaxException, MalformedURLException {
        String targetURL = "http://news.example.org/a.html";
        Outlink outlink = new Outlink(targetURL);

        // lenient (default): same registered domain, allowed without any robots lookup
        ProtocolFactory lenientFactory = mock(ProtocolFactory.class);
        newsBolt().setProtocolFactory(lenientFactory);
        assertTrue(
                "Same registered domain must be allowed in lenient mode",
                newsBolt().crossSubmitCheck(outlink, SITEMAP_URL, new Metadata()));
        verifyNoInteractions(lenientFactory);

        // strict: exact hosts differ, falls through to the robots.txt check
        setupParserBolt(new NewsSiteMapParserBolt());
        Map<String, Object> config = baseConfig();
        config.put("crossSubmit.lenient", false);
        prepareParserBolt("test.parsefilters.json", config);

        ProtocolFactory strictFactory = mock(ProtocolFactory.class);
        Protocol protocol = mock(Protocol.class);
        when(strictFactory.getProtocol(any(URL.class))).thenReturn(protocol);
        BaseRobotRules rules = mock(BaseRobotRules.class);
        when(protocol.getRobotRules(targetURL)).thenReturn(rules);
        when(rules.getSitemaps()).thenReturn(Collections.emptyList());
        newsBolt().setProtocolFactory(strictFactory);

        assertFalse(
                "Different host must be rejected in strict mode if robots.txt does not"
                        + " reference the sitemap",
                newsBolt().crossSubmitCheck(outlink, SITEMAP_URL, new Metadata()));
        verify(protocol).getRobotRules(targetURL);
    }

    /**
     * With crossSubmit.allowed=true the check is skipped entirely: all outlinks are emitted as
     * DISCOVERED and neither robots.txt nor the protocol factory are ever consulted.
     */
    @Test
    public void testCrossSubmitAllowedBypassesCheck() throws IOException {
        setupParserBolt(new NewsSiteMapParserBolt());
        Map<String, Object> config = baseConfig();
        config.put("crossSubmit.allowed", true);
        prepareParserBolt("test.parsefilters.json", config);

        ProtocolFactory protocolFactory = mock(ProtocolFactory.class);
        newsBolt().setProtocolFactory(protocolFactory);

        executeWithContent(readRecentContent("cross-sitemap-news.xml"));

        assertEquals(1, countStatusEmissions(ARTICLE_ORG, Status.DISCOVERED));
        assertEquals(1, countStatusEmissions(ARTICLE_NET, Status.DISCOVERED));
        assertEquals(1, countStatusEmissions(ARTICLE_COM, Status.DISCOVERED));
        assertEquals(1, countStatusEmissions(SITEMAP_URL, Status.FETCHED));

        verifyNoInteractions(protocolFactory);
    }

    /**
     * A cross-host outlink is allowed without any robots.txt lookup when the sitemap's url.path
     * trail (recorded by metadata.track.path) shows the sitemap was discovered via the target's own
     * host, e.g. through its robots.txt and sitemap index chain.
     */
    @Test
    public void testTrailAllowsCrossHostWithoutRobotsLookup()
            throws URISyntaxException, MalformedURLException {
        ProtocolFactory protocolFactory = mock(ProtocolFactory.class);
        newsBolt().setProtocolFactory(protocolFactory);

        // the sitemap (hosted on www.example.org) was discovered via a sitemap
        // index on www.example.com, the host of the outlink to check
        Metadata sitemapMetadata = new Metadata();
        sitemapMetadata.addValues(
                MetadataTransfer.urlPathKeyName,
                Collections.singletonList("http://www.example.com/sitemap-index.xml"));

        Outlink outlink = new Outlink(ARTICLE_COM);
        assertTrue(
                "Outlink must be allowed when the sitemap was discovered via the target host",
                newsBolt().crossSubmitCheck(outlink, SITEMAP_URL, sitemapMetadata));

        // tier 2 decided without consulting robots.txt
        verifyNoInteractions(protocolFactory);
    }

    /**
     * numLinks must count only the outlinks actually emitted, not all parsed ones: a sitemap with
     * numLinks == 0 can be retired by the NewsSitemapScheduler, which must equally apply to
     * sitemaps whose links are all rejected by the cross-submit check.
     */
    @Test
    public void testNumLinksCountsOnlyEmittedOutlinks() throws IOException {
        ProtocolFactory protocolFactory = mock(ProtocolFactory.class);
        Protocol protocol = mock(Protocol.class);
        when(protocolFactory.getProtocol(any(URL.class))).thenReturn(protocol);
        BaseRobotRules rules = mock(BaseRobotRules.class);
        when(protocol.getRobotRules(anyString())).thenReturn(rules);
        when(rules.getSitemaps()).thenReturn(Collections.emptyList());
        newsBolt().setProtocolFactory(protocolFactory);

        executeWithContent(readRecentContent("cross-sitemap-news.xml"));

        // 3 outlinks parsed, but only the same-host one is emitted
        Metadata sitemapMetadata = fetchedMetadata(SITEMAP_URL);
        assertEquals("1", sitemapMetadata.getFirstValue(NewsSiteMapParserBolt.numLinksKey));
    }

    private Metadata fetchedMetadata(String url) {
        for (List<Object> values : statusEmissions()) {
            if (url.equals(values.get(0)) && Status.FETCHED.equals(values.get(2))) {
                return (Metadata) values.get(1);
            }
        }
        fail("No FETCHED emission for " + url);
        return null;
    }
}
