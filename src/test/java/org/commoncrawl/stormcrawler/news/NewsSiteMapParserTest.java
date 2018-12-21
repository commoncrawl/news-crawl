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

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.storm.task.OutputCollector;
import org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt.SitemapType;
import org.junit.Before;
import org.junit.Test;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.TestOutputCollector;
import com.digitalpebble.stormcrawler.TestUtil;
import com.digitalpebble.stormcrawler.parse.Outlink;

import crawlercommons.sitemaps.UnknownFormatException;

public class NewsSiteMapParserTest {

    NewsSiteMapParserBolt bolt;

    @Before
    public void setup() {
        bolt = new NewsSiteMapParserBolt();
        Map<String, Object> config = new HashMap<>();
        config.put("sitemap.sniffContent", true);
        // allow items published during the last week
        config.put("sitemap.filter.hours.since.modified", 168);
        bolt.prepare(config, TestUtil.getMockedTopologyContext(),
                new OutputCollector(new TestOutputCollector()));
    }

    @Test
    public void testSiteMapParser() throws IOException, UnknownFormatException {
        String url = "https://example.org/sitemap-news.xml";
        byte[] content = readContent("sitemap-news.xml");
        String contentType = "";
        Metadata parentMetadata = new Metadata();
        List<Outlink> links = new ArrayList<>();

        SitemapType type = bolt.detectContent(url, content);
        assertEquals(SitemapType.NEWS, type);

        bolt.parseSiteMap(url, content, contentType, parentMetadata, links);

        // unmodified sitemap:
        // - publication date is far in the past, link should be skipped
        // <news:publication_date>2008-12-23</news:publication_date>
        assertEquals("Outdated link not skipped", 0, links.size());

        // now set the publication date to yesterday
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        content = (new String(content, StandardCharsets.UTF_8)).replace(
                "<news:publication_date>2008-12-23</news:publication_date>",
                "<news:publication_date>"
                        + yesterday.format(
                                DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        + "</news:publication_date>")
                .getBytes(StandardCharsets.UTF_8);
        bolt.parseSiteMap(url, content, contentType, parentMetadata, links);
        assertEquals(
                "Expected one <loc> and one additional <xhtml:link> link - image links are ignored",
                2, links.size());
    }

    protected byte[] readContent(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(getClass().getClassLoader().getResourceAsStream(filename),
                baos);
        return baos.toByteArray();
    }
}
