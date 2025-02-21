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
		String sitemapURL = "https://example.org/sitemap-news.xml";
		String articleURL = "http://www.example.org/business/article55.html";
		String crossHostUrl = "http://www.example.net/ads/sponsored-content.html";

		// Mock RobotRules and its dependencies
		ProtocolFactory mockProtocolFactory = mock(ProtocolFactory.class);
		Protocol mockProtocol = mock(Protocol.class);
		BaseRobotRules mockRules = mock(BaseRobotRules.class);
		when(mockProtocolFactory.getProtocol(any(URL.class))).thenReturn(mockProtocol);
		when(mockProtocol.getRobotRules(any(String.class))).thenReturn(mockRules);

		when(mockRules.getSitemaps()).thenReturn(Collections.singletonList(sitemapURL));
		when(mockRules.isAllowed(articleURL)).thenReturn(true);
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
		assertEquals(2, links.size());
    	assertTrue(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(0), sitemapURL));
    	assertFalse(((NewsSiteMapParserBolt) bolt).crossSubmitCheck(links.get(1), sitemapURL));
	}
}
