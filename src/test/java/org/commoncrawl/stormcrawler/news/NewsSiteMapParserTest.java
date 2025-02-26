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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.digitalpebble.stormcrawler.protocol.Protocol;
import com.digitalpebble.stormcrawler.protocol.ProtocolFactory;
import com.digitalpebble.stormcrawler.util.MetadataTransfer;
import crawlercommons.robots.BaseRobotRules;
import org.apache.commons.io.IOUtils;
import org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt.SitemapType;
import org.junit.Before;
import org.junit.Test;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.parse.Outlink;
import com.digitalpebble.stormcrawler.parse.ParsingTester;

import crawlercommons.sitemaps.UnknownFormatException;

public class NewsSiteMapParserTest extends ParsingTester {

    @Before
    public void setupParserBolt() {
	setupParserBolt(new NewsSiteMapParserBolt());
	Map<String, Object> config = new HashMap<>();
	config.put("sitemap.sniffContent", true);
	// allow items published during the last week
	config.put("sitemap.filter.hours.since.modified", 168);
	config.put("http.agent.name", " Mozilla/5.0 (compatible; NewsBot/1.0; +http://example.com/bot)");
	prepareParserBolt("test.parsefilters.json", config);
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

	((NewsSiteMapParserBolt) bolt).parseSiteMap(url, content, contentType, parentMetadata, links);

	// unmodified sitemap:
	// - publication date is far in the past, link should be skipped
	// <news:publication_date>2008-12-23</news:publication_date>
	assertEquals("Outdated link not skipped", 0, links.size());

	// now set the publication date to yesterday
	LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
	content = (new String(content, StandardCharsets.UTF_8))
		.replace("<news:publication_date>2008-12-23</news:publication_date>", "<news:publication_date>"
			+ yesterday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "</news:publication_date>")
		.getBytes(StandardCharsets.UTF_8);
	((NewsSiteMapParserBolt) bolt).parseSiteMap(url, content, contentType, parentMetadata, links);

	assertEquals("Expected one <loc> and one additional <xhtml:link> link - image links are ignored", 2,
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
         assertNotEquals("RSS feed with sitemap namespace should not be detected as sitemap",
                 SitemapType.NEWS, type);
         assertNotEquals("RSS feed with sitemap namespace should not be detected as sitemap",
                 SitemapType.SITEMAP, type);
     }
	@Test
	public void testCrossHostSitemapVerification() throws IOException, UnknownFormatException {
		String sitemapURL = "https://www.example.org/sitemap-news.xml";
		String articleURL = "http://www.example.org/business/article55.html";
		String adSitemapURL = "https://www.example.net/sitemap-ads.xml";

		// Mock RobotRules and its dependencies
		ProtocolFactory mockProtocolFactory = mock(ProtocolFactory.class);
		Protocol mockProtocol = mock(Protocol.class);
		when(mockProtocolFactory.getProtocol(any(URL.class))).thenReturn(mockProtocol);

		BaseRobotRules mockRules = mock(BaseRobotRules.class);
		when(mockProtocol.getRobotRules(articleURL)).thenReturn(mockRules);
		when(mockRules.getSitemaps()).thenReturn(Collections.singletonList(sitemapURL));

		BaseRobotRules mockRules1 = mock(BaseRobotRules.class);
		when(mockProtocol.getRobotRules(any(String.class))).thenReturn(mockRules1);
		when(mockRules.getSitemaps()).thenReturn(Collections.singletonList(adSitemapURL));
		// Set up test data
		byte[] content = readContent("cross-sitemap-news.xml");
		String contentType = "";
		Metadata parentMetadata = new Metadata();
		List<Outlink> links = new ArrayList<>();

		// Set recent publication date and cross-host URL
		LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
		content = (new String(content, StandardCharsets.UTF_8))
					.replace("<news:publication_date>2008-12-23</news:publication_date>",
						"<news:publication_date>" + yesterday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "</news:publication_date>")
				.getBytes(StandardCharsets.UTF_8);

		// Inject mocked protocol factory
		((NewsSiteMapParserBolt) bolt).setProtocolFactory(mockProtocolFactory);

		((NewsSiteMapParserBolt) bolt).parseSiteMap(sitemapURL, content, contentType, parentMetadata, links);
		// Verify the cross-host link is allowed and included
		assertEquals(3, links.size());
    	assertTrue(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(0), sitemapURL, parentMetadata));
    	assertFalse(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(1), sitemapURL, parentMetadata));
	}

	/**
	 * Tests cross-host sitemap submissions with the following structure:
	 *
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
	 *
	 * URLs from example.org and example.com pass crossSubmitCheck since their robots.txt
	 * reference the same sitemap index which contains the sitemap from which the link is fetched.
	 * URLs from example.net fail since their robots reference a different sitemap index.
	 */
	@Test
    public void test_cross_host_submission_sitemaps() throws IOException, UnknownFormatException {
		String sitemapURL = "https://www.example.org/sitemap-news.xml";
		String sitemapIndexURL = "https://www.example.org/sitemap-index.xml";
		String adSitemapURL = "https://www.example.net/sitemap-ads.xml";


		// Mock RobotRules and its dependencies
		ProtocolFactory mockProtocolFactory = mock(ProtocolFactory.class);
		Protocol mockProtocol = mock(Protocol.class);
		when(mockProtocolFactory.getProtocol(any(URL.class))).thenReturn(mockProtocol);

		BaseRobotRules mockRules = mock(BaseRobotRules.class);
		when(mockProtocol.getRobotRules("http://www.example.org/business/article55.html")).thenReturn(mockRules);
		when(mockRules.getSitemaps()).thenReturn(Collections.singletonList(sitemapIndexURL));

		BaseRobotRules mockRules1 = mock(BaseRobotRules.class);
		when(mockProtocol.getRobotRules("http://www.example.com/sports/news1.html")).thenReturn(mockRules1);
		when(mockRules1.getSitemaps()).thenReturn(Collections.singletonList(sitemapIndexURL));

		BaseRobotRules mockRules2 = mock(BaseRobotRules.class);
		when(mockProtocol.getRobotRules("http://www.example.net/ads/sponsored-content.html")).thenReturn(mockRules2);
		when(mockRules2.getSitemaps()).thenReturn(Collections.singletonList(adSitemapURL));

        // Mocking MetadataTransfer to return specific url.path metadata
        MetadataTransfer metadataTransferMock = mock(MetadataTransfer.class);
        Metadata targetMetadata = new Metadata();
        targetMetadata.addValues("url.path", Arrays.asList(sitemapIndexURL, sitemapURL));
        when(metadataTransferMock.getMetaForOutlink(anyString(), anyString(), any(Metadata.class)))
            .thenReturn(targetMetadata);

        // Injecting the mock into the bolt
        ((NewsSiteMapParserBolt) bolt).setMetadataTransfer(metadataTransferMock);

		// Set up test data
		byte[] content = readContent("cross-sitemap-news.xml");
		String contentType = "";
		Metadata parentMetadata = new Metadata();
		List<Outlink> links = new ArrayList<>();

		// Set recent publication date and cross-host URL
		LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
		content = (new String(content, StandardCharsets.UTF_8))
					.replace("<news:publication_date>2008-12-23</news:publication_date>",
						"<news:publication_date>" + yesterday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "</news:publication_date>")
				.getBytes(StandardCharsets.UTF_8);

		// Inject mocked protocol factory
		((NewsSiteMapParserBolt) bolt).setProtocolFactory(mockProtocolFactory);

		((NewsSiteMapParserBolt) bolt).parseSiteMap(sitemapURL, content, contentType, parentMetadata, links);
		// Verify the cross-host link is allowed and included
		assertEquals(3, links.size());
    	assertTrue(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(0), sitemapURL, parentMetadata));
    	assertFalse(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(1), sitemapURL, parentMetadata));
		assertTrue(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(2), sitemapURL, parentMetadata));
    }
}
