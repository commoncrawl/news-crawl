package org.commoncrawl.stormcrawler.news;

import static com.digitalpebble.stormcrawler.Constants.StatusStreamName;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.commoncrawl.stormcrawler.news.bootstrap.ContentDetector;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.bolt.SiteMapParserBolt;
import com.digitalpebble.stormcrawler.parse.Outlink;
import com.digitalpebble.stormcrawler.parse.ParseData;
import com.digitalpebble.stormcrawler.parse.ParseFilter;
import com.digitalpebble.stormcrawler.parse.ParseFilters;
import com.digitalpebble.stormcrawler.parse.ParseResult;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.protocol.HttpHeaders;
import com.digitalpebble.stormcrawler.util.ConfUtils;

import crawlercommons.sitemaps.AbstractSiteMap;
import crawlercommons.sitemaps.SiteMap;
import crawlercommons.sitemaps.SiteMapIndex;
import crawlercommons.sitemaps.SiteMapURL;
import crawlercommons.sitemaps.SiteMapURL.ChangeFrequency;
import crawlercommons.sitemaps.UnknownFormatException;


/**
 * ParserBolt for <link href=
 * "https://support.google.com/news/publisher/answer/74288?hl=en">news
 * sitemaps</a>.
 */
@SuppressWarnings("serial")
public class NewsSiteMapParserBolt extends SiteMapParserBolt {
    // TODO:
    //    this is a modified copy of c.d.s.bolt.SiteMapParserBolt
    //    - make parent class extensible and overridable
    //    modifications:
    //    - detect and process only Google news sitemaps
    //    - pass "isSitemapNews" to status metadata

    public static final String isSitemapNewsKey = "isSitemapNews";

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(NewsSiteMapParserBolt.class);

    public static String[][] contentClues = {
            // match 0-n: a news sitemap
            { "http://www.google.com/schemas/sitemap-news/0.9" },
            { "https://www.google.com/schemas/sitemap-news/0.9" },
            { "http://www.google.com/schemas/sitemap-news/0.84" },
            // match > n: a sitemap, but not a news sitemap
            { "http://www.sitemaps.org/schemas/sitemap/0.9" },
            { "https://www.sitemaps.org/schemas/sitemap/0.9" },
            { "http://www.google.com/schemas/sitemap/0.9" },
            { "http://www.google.com/schemas/sitemap/0.84" }};
    public static int contentCluesSitemapNewsMatchUpTo = 2;

    protected static final int maxOffsetContentGuess = 1024;
    private static ContentDetector contentDetector = new ContentDetector(
            NewsSiteMapParserBolt.contentClues, maxOffsetContentGuess);

    private boolean strictMode = false;
    private boolean sniffWhenNoSMKey = false;

    private ParseFilter parseFilters;
    private int filterHoursSinceModified = -1;

    @Override
    public void execute(Tuple tuple) {
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        // TODO check that we have the right number of fields?
        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");

        boolean isSitemap = Boolean.valueOf(
                metadata.getFirstValue(SiteMapParserBolt.isSitemapKey));
        boolean isNewsSitemap = Boolean
                .valueOf(metadata.getFirstValue(isSitemapNewsKey));
        // doesn't have the metadata expected
        if (!isNewsSitemap || !isSitemap) {
            if (sniffWhenNoSMKey) {
                // try based on the first bytes?
                // works for XML and non-compressed documents
                int match = contentDetector.getFirstMatch(content);
                if (match >= 0) {
                    // a sitemap, not necessarily a news sitemap
                    isSitemap = true;
                    metadata.setValue(SiteMapParserBolt.isSitemapKey, "true");
                    if (match <= contentCluesSitemapNewsMatchUpTo) {
                        isNewsSitemap = true;
                        LOG.info("{} detected as news sitemap based on content",
                                url);
                        metadata.setValue(isSitemapNewsKey, "true");
                    }
                }
            }

        }

        if (!isNewsSitemap) {
            if (isSitemap) {
                // a sitemap but not a news sitemap
                collector.emit(Constants.StatusStreamName, tuple,
                        new Values(url, metadata, Status.FETCHED));
            } else {
                // not a sitemap, just pass it on
                collector.emit(tuple, tuple.getValues());
            }
            collector.ack(tuple);
            return;
        }

        String ct = metadata.getFirstValue(HttpHeaders.CONTENT_TYPE);

        List<Outlink> outlinks;
        try {
            outlinks = parseSiteMap(url, content, ct, metadata);
        } catch (Exception e) {
            // exception while parsing the sitemap
            String errorMessage = "Exception while parsing " + url + ": " + e;
            LOG.error(errorMessage);
            // send to status stream in case another component wants to update
            // its status
            metadata.setValue(Constants.STATUS_ERROR_SOURCE, "sitemap parsing");
            metadata.setValue(Constants.STATUS_ERROR_MESSAGE, errorMessage);
            collector.emit(Constants.StatusStreamName, tuple, new Values(url,
                    metadata, Status.ERROR));
            collector.ack(tuple);
            return;
        }

        // apply the parse filters if any to the current document
        try {
            ParseResult parse = new ParseResult();
            parse.setOutlinks(outlinks);
            ParseData parseData = parse.get(url);
            parseData.setMetadata(metadata);

            parseFilters.filter(url, content, null, parse);
        } catch (RuntimeException e) {
            String errorMessage = "Exception while running parse filters on "
                    + url + ": " + e;
            LOG.error(errorMessage);
            metadata.setValue(Constants.STATUS_ERROR_SOURCE,
                    "content filtering");
            metadata.setValue(Constants.STATUS_ERROR_MESSAGE, errorMessage);
            collector.emit(StatusStreamName, tuple, new Values(url, metadata,
                    Status.ERROR));
            collector.ack(tuple);
            return;
        }

        // send to status stream
        for (Outlink ol : outlinks) {
            Values v = new Values(ol.getTargetURL(), ol.getMetadata(),
                    Status.DISCOVERED);
            collector.emit(Constants.StatusStreamName, tuple, v);
        }

        // marking the main URL as successfully fetched
        // regardless of whether we got a parse exception or not
        collector.emit(Constants.StatusStreamName, tuple, new Values(url,
                metadata, Status.FETCHED));
        collector.ack(tuple);
    }

    private List<Outlink> parseSiteMap(String url, byte[] content,
            String contentType, Metadata parentMetadata)
            throws UnknownFormatException, IOException {

        crawlercommons.sitemaps.SiteMapParser parser = new crawlercommons.sitemaps.SiteMapParser(
                strictMode);

        URL sURL = new URL(url);
        AbstractSiteMap siteMap;
        // let the parser guess what the mimetype is
        if (StringUtils.isBlank(contentType)
                || contentType.contains("octet-stream")) {
            siteMap = parser.parseSiteMap(content, sURL);
        } else {
            siteMap = parser.parseSiteMap(contentType, content, sURL);
        }

        List<Outlink> links = new ArrayList<>();

        if (siteMap.isIndex()) {
            SiteMapIndex smi = (SiteMapIndex) siteMap;
            Collection<AbstractSiteMap> subsitemaps = smi.getSitemaps();
            // keep the subsitemaps as outlinks
            // they will be fetched and parsed in the following steps
            Iterator<AbstractSiteMap> iter = subsitemaps.iterator();
            while (iter.hasNext()) {
                AbstractSiteMap asm = iter.next();
                String target = asm.getUrl().toExternalForm();

                Date lastModified = asm.getLastModified();
                if (lastModified != null) {
                    // filter based on the published date
                    if (filterHoursSinceModified != -1) {
                        Calendar rightNow = Calendar.getInstance();
                        rightNow.add(Calendar.HOUR, -filterHoursSinceModified);
                        if (lastModified.before(rightNow.getTime())) {
                            LOG.info(
                                    "{} has a modified date {} which is more than {} hours old",
                                    target, lastModified.toString(),
                                    filterHoursSinceModified);
                            continue;
                        }
                    }
                }

                Outlink ol = filterOutlink(sURL, target, parentMetadata,
                        isSitemapKey, "true", isSitemapNewsKey, "true");
                if (ol == null) {
                    continue;
                }
                links.add(ol);
                LOG.debug("{} : [sitemap] {}", url, target);
            }
        }
        // sitemap files
        else {
            SiteMap sm = (SiteMap) siteMap;
            // TODO see what we can do with the LastModified info
            Collection<SiteMapURL> sitemapURLs = sm.getSiteMapUrls();
            Iterator<SiteMapURL> iter = sitemapURLs.iterator();
            while (iter.hasNext()) {
                SiteMapURL smurl = iter.next();
                // TODO handle priority in metadata
                double priority = smurl.getPriority();
                // TODO convert the frequency into a numerical value and handle
                // it in metadata
                ChangeFrequency freq = smurl.getChangeFrequency();

                String target = smurl.getUrl().toExternalForm();

                Date lastModified = smurl.getLastModified();
                if (lastModified != null) {
                    // filter based on the published date
                    // TODO: should also consider
                    //        <news:publication_date>2008-12-23</news:publication_date>
                    if (filterHoursSinceModified != -1) {
                        Calendar rightNow = Calendar.getInstance();
                        rightNow.add(Calendar.HOUR, -filterHoursSinceModified);
                        if (lastModified.before(rightNow.getTime())) {
                            LOG.info(
                                    "{} has a modified date {} which is more than {} hours old",
                                    target, lastModified.toString(),
                                    filterHoursSinceModified);
                            continue;
                        }
                    }
                }

                Outlink ol = filterOutlink(sURL, target, parentMetadata,
                        isSitemapKey, "false", isSitemapNewsKey, "false");
                if (ol == null) {
                    continue;
                }
                links.add(ol);
                LOG.debug("{} : [sitemap] {}", url, target);
            }
        }

        return links;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void prepare(Map stormConf, TopologyContext context,
            OutputCollector collector) {
        super.prepare(stormConf, context, collector);
        sniffWhenNoSMKey = ConfUtils.getBoolean(stormConf,
                "sitemap.sniffContent", false);
        filterHoursSinceModified = ConfUtils.getInt(stormConf,
                "sitemap.filter.hours.since.modified", -1);
        parseFilters = ParseFilters.fromConf(stormConf);
    }

}
