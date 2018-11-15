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
package org.commoncrawl.stormcrawler.news.bootstrap;

import java.util.Map;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.commoncrawl.stormcrawler.news.ContentDetector;
import org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.bolt.SiteMapParserBolt;
import com.digitalpebble.stormcrawler.parse.ParseData;
import com.digitalpebble.stormcrawler.parse.ParseFilter;
import com.digitalpebble.stormcrawler.parse.ParseFilters;
import com.digitalpebble.stormcrawler.parse.ParseResult;
import com.digitalpebble.stormcrawler.persistence.Status;

/**
 * Detector for <link href=
 * "https://support.google.com/news/publisher/answer/74288?hl=en">news
 * sitemaps</a> and also <a href="http://www.sitemaps.org/">sitemaps</a>.
 */
@SuppressWarnings("serial")
public class NewsSiteMapDetectorBolt extends SiteMapParserBolt {

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(NewsSiteMapDetectorBolt.class);

    protected static final int maxOffsetContentGuess = 1024;
    private static ContentDetector contentDetector = new ContentDetector(
            NewsSiteMapParserBolt.contentClues, maxOffsetContentGuess);

    private ParseFilter parseFilters;


    @Override
    public void execute(Tuple tuple) {
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");

        boolean isSitemap = Boolean.valueOf(
                metadata.getFirstValue(SiteMapParserBolt.isSitemapKey));
        boolean isNewsSitemap = Boolean.valueOf(
                metadata.getFirstValue(NewsSiteMapParserBolt.isSitemapNewsKey));

        if (!isNewsSitemap || !isSitemap) {
            int match = contentDetector.getFirstMatch(content);
            if (match >= 0) {
                // a sitemap, not necessarily a news sitemap
                isSitemap = true;
                metadata.setValue(SiteMapParserBolt.isSitemapKey, "true");
                if (match <= NewsSiteMapParserBolt.contentCluesSitemapNewsMatchUpTo) {
                    isNewsSitemap = true;
                    LOG.info("{} detected as news sitemap based on content",
                            url);
                    metadata.setValue(NewsSiteMapParserBolt.isSitemapNewsKey,
                            "true");
                }
            }
        }

        if (isSitemap) {
            // do not parse but run parse filters
            ParseResult parse = new ParseResult();
            ParseData parseData = parse.get(url);
            parseData.setMetadata(metadata);
            parseFilters.filter(url, content, null, parse);
            // emit status
            collector.emit(Constants.StatusStreamName, tuple,
                    new Values(url, metadata, Status.FETCHED));
        } else {
            // pass on
            collector.emit(tuple, tuple.getValues());
        }
        collector.ack(tuple);
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void prepare(Map stormConf, TopologyContext context,
            OutputCollector collect) {
        super.prepare(stormConf, context, collect);
        parseFilters = ParseFilters.fromConf(stormConf);
    }

}
