package org.commoncrawl.news.bootstrap;

import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.NewsSiteMapParserBolt;
import com.digitalpebble.stormcrawler.bolt.SiteMapParserBolt;
import com.digitalpebble.stormcrawler.persistence.Status;

/**
 * Detector for <link href=
 * "https://support.google.com/news/publisher/answer/74288?hl=en">news
 * sitemaps</a> and also <a href="http://www.sitemaps.org/">sitemaps</a>.
 */
public class NewsSiteMapDetectorBolt extends SiteMapParserBolt {

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(NewsSiteMapDetectorBolt.class);

    protected static final int maxOffsetContentGuess = 1024;
    private static ContentDetector contentDetector = new ContentDetector(
            NewsSiteMapParserBolt.contentClues, maxOffsetContentGuess);


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
                if (match == 0) {
                    isNewsSitemap = true;
                    LOG.info("{} detected as news sitemap based on content",
                            url);
                    metadata.setValue(NewsSiteMapParserBolt.isSitemapNewsKey,
                            "true");
                }
            }
        }

        if (isSitemap) {
            // emit status
            collector.emit(Constants.StatusStreamName, tuple,
                    new Values(url, metadata, Status.FETCHED));
        } else {
            // pass on
            collector.emit(tuple, tuple.getValues());
        }
        collector.ack(tuple);
    }
}
