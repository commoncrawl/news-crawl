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
import org.apache.storm.metric.api.MeanReducer;
import org.apache.storm.metric.api.ReducedMetric;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.bolt.SiteMapParserBolt;
import com.digitalpebble.stormcrawler.parse.Outlink;
import com.digitalpebble.stormcrawler.parse.ParseData;
import com.digitalpebble.stormcrawler.parse.ParseFilter;
import com.digitalpebble.stormcrawler.parse.ParseFilters;
import com.digitalpebble.stormcrawler.parse.ParseResult;
import com.digitalpebble.stormcrawler.persistence.DefaultScheduler;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.protocol.HttpHeaders;
import com.digitalpebble.stormcrawler.util.ConfUtils;

import crawlercommons.sitemaps.AbstractSiteMap;
import crawlercommons.sitemaps.Namespace;
import crawlercommons.sitemaps.SiteMap;
import crawlercommons.sitemaps.SiteMapIndex;
import crawlercommons.sitemaps.SiteMapParser;
import crawlercommons.sitemaps.SiteMapURL;
import crawlercommons.sitemaps.SiteMapURL.ChangeFrequency;
import crawlercommons.sitemaps.UnknownFormatException;
import crawlercommons.sitemaps.extension.Extension;
import crawlercommons.sitemaps.extension.ExtensionMetadata;
import crawlercommons.sitemaps.extension.LinkAttributes;
import crawlercommons.sitemaps.extension.NewsAttributes;


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
    //    - or a sitemapindex because some subsitemaps may
    //      be news sitemaps
    //    - pass "isSitemapNews" to status metadata

    public static enum SitemapType {
        NEWS, INDEX, SITEMAP
    }

    public static final String isSitemapNewsKey = "isSitemapNews";
    public static final String isSitemapIndexKey = "isSitemapIndex";
    /**
     * A sitemap (not necessarily a news sitemap) which is verified to contain
     * links to news articles. Necessary to crawl news sites which provide a
     * sitemap but neither a news feed or sitemap.
     */
    public static final String isSitemapVerifiedKey = "isSitemapVerified";

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(NewsSiteMapParserBolt.class);

    /* content clues for news sitemaps, sitemap indexes or any sitemaps */
    public static String[][] contentClues;
    public static int contentCluesSitemapNewsMatchUpTo = -1;
    public static int contentCluesSitemapIndexMatchUpTo = -1;
    static {
        int cluesSize = Namespace.NEWS.length + 1 + 1 + Namespace.SITEMAP_LEGACY.length;
        contentClues = new String[cluesSize][1];
        int j = 0;
        // match 0-m: a news sitemap
        for (int i = 0; i < Namespace.NEWS.length; i++, j++) {
            contentClues[j][0] = Namespace.NEWS[i];
            contentCluesSitemapNewsMatchUpTo = j;
        }
        // m < match <= n: a sitemapindex
        contentClues[j][0] = "<sitemapindex";
        contentCluesSitemapIndexMatchUpTo = j;
        j++;
        // match > n: a "simple" sitemap
        contentClues[j][0] = Namespace.SITEMAP;
        j++;
        for (int i = 0; i < Namespace.SITEMAP_LEGACY.length; i++, j++) {
            contentClues[j][0] = Namespace.SITEMAP_LEGACY[i];
        }
    }

    private static ContentDetector contentDetector;

    private boolean strictModeSitemaps = false;
    private boolean allowPartialSitemaps = true;
    private boolean sniffContent = false;

    private ParseFilter parseFilters;
    private int filterHoursSinceModified = -1;

    private ReducedMetric averagedMetrics;

    /** Delay in minutes used for scheduling sub-sitemaps **/
    private int scheduleSitemapsWithDelay = -1;

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
        boolean isSitemapIndex = Boolean
                .valueOf(metadata.getFirstValue(isSitemapIndexKey));
        boolean isSitemapVerified = Boolean
                .valueOf(metadata.getFirstValue(isSitemapVerifiedKey));

        if (sniffContent) {
            SitemapType type = detectContent(url, content);
            if (type != null) {
                isSitemap = true;
                metadata.setValue(SiteMapParserBolt.isSitemapKey, "true");
                if (type == SitemapType.NEWS) {
                    isNewsSitemap = true;
                    metadata.setValue(isSitemapNewsKey, "true");
                } else if (type == SitemapType.INDEX) {
                    isSitemapIndex = true;
                    if (isNewsSitemap) {
                        metadata.setValue(isSitemapNewsKey, "false");
                    }
                    isNewsSitemap = false;
                    metadata.setValue(isSitemapIndexKey, "true");
                } else if (isSitemapVerified) {
                    // do not reset metadata for verified sitemaps
                } else {
                    // sitemaps may change: reset wrong metadata values from
                    // previous fetches
                    if (isNewsSitemap) {
                        metadata.setValue(isSitemapNewsKey, "false");
                    }
                    if (isSitemapIndex) {
                        metadata.setValue(isSitemapIndexKey, "false");
                    }
                    isNewsSitemap = false;
                    isSitemapIndex = false;
                }
            }
        }

        if (isNewsSitemap || isSitemapIndex || isSitemapVerified) {
            /*
             * remove the isSitemap key from metadata to avoid that the default
             * sitemap fetch interval is applied to news sitemaps, sitemap
             * indexes and verified sitemaps
             */
            metadata.remove(isSitemapKey);
        } else {
            if (isSitemap) {
                collector.emit(Constants.StatusStreamName, tuple,
                        new Values(url, metadata, Status.FETCHED));
            } else {
                // not a sitemap, just pass it on
                collector.emit(tuple, tuple.getValues());
            }
            collector.ack(tuple);
            // skip everything which is not a news sitemap or a sitemap index
            return;
        }

        String ct = metadata.getFirstValue(HttpHeaders.CONTENT_TYPE);

        AbstractSiteMap sitemap;
        List<Outlink> outlinks = new ArrayList<>();

        try {
            sitemap = parseSiteMap(url, content, ct, metadata, outlinks);
        } catch (Exception e) {
            // exception while parsing the sitemap
            String errorMessage = "Exception while parsing " + url + ": " + e;
            LOG.error(errorMessage);
            // send to status stream in case another component wants to update
            // its status
            metadata.setValue(Constants.STATUS_ERROR_SOURCE, "sitemap parsing");
            metadata.setValue(Constants.STATUS_ERROR_MESSAGE, errorMessage);
            metadata.remove("numLinks");
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
            metadata.remove("numLinks");
            collector.emit(StatusStreamName, tuple, new Values(url, metadata,
                    Status.ERROR));
            collector.ack(tuple);
            return;
        }

        // check whether parsed sitemap is index
        if (sitemap.isIndex()) {
            isSitemapIndex = true;
            metadata.setValue(isSitemapIndexKey, "true");
        } else {
            isSitemapIndex = false;
            metadata.remove(isSitemapIndexKey);
        }

        // send outlinks to status stream
        for (Outlink ol : outlinks) {
            if (isSitemapIndex) {
                ol.getMetadata().setValue(isSitemapKey, "true");
                if (isSitemapVerified) {
                    // mark sitemaps from verified sitemap index also as "verified"
                    ol.getMetadata().setValue(isSitemapVerifiedKey, "true");
                }
            }
            Values v = new Values(ol.getTargetURL(), ol.getMetadata(),
                    Status.DISCOVERED);
            collector.emit(Constants.StatusStreamName, tuple, v);
        }

        // track the number of links found in the sitemap
        metadata.setValue("numLinks", String.valueOf(outlinks.size()));

        // marking the main URL as successfully fetched
        collector.emit(Constants.StatusStreamName, tuple, new Values(url,
                metadata, Status.FETCHED));
        collector.ack(tuple);
    }

    public SitemapType detectContent(String url, byte[] content) {
        // try to detect content based on the first n bytes
        // works for XML and non-compressed documents
        // TODO: implement check for compressed XML
        int match = contentDetector.getFirstMatch(content);
        if (match >= 0) {
            // a sitemap, need to detect type of sitemap
            if (match <= contentCluesSitemapNewsMatchUpTo) {
                LOG.info("{} detected as news sitemap based on content",
                        url);
                return SitemapType.NEWS;
            } else if (match <= contentCluesSitemapIndexMatchUpTo) {
                LOG.info("{} detected as sitemap index based on content",
                        url);
                return SitemapType.INDEX;
            } else {
                return SitemapType.SITEMAP;
            }
        }
        return null;
    }

    private boolean recentlyModified(Date lastModified) {
        if (lastModified != null && filterHoursSinceModified != -1) {
            // filter based on the published date
            Calendar rightNow = Calendar.getInstance();
            rightNow.add(Calendar.HOUR, -filterHoursSinceModified);
            if (lastModified.before(rightNow.getTime())) {
                return false;
            }
        }
        return true;
    }

    protected AbstractSiteMap parseSiteMap(String url, byte[] content,
            String contentType, Metadata parentMetadata, List<Outlink> links)
            throws UnknownFormatException, IOException {

        SiteMapParser parser = new SiteMapParser(strictModeSitemaps,
                allowPartialSitemaps);
        parser.setStrictNamespace(true);
        parser.addAcceptedNamespace(Namespace.SITEMAP_LEGACY);
        parser.addAcceptedNamespace(Namespace.EMPTY);
        // enable extensions (also adds extension namespaces)
        parser.enableExtension(Extension.NEWS);
        parser.enableExtension(Extension.LINKS);

        URL sURL = new URL(url);
        long start = System.currentTimeMillis();
        AbstractSiteMap siteMap;
        // let the parser guess what the mimetype is
        if (StringUtils.isBlank(contentType)
                || contentType.contains("octet-stream")) {
            siteMap = parser.parseSiteMap(content, sURL);
        } else {
            siteMap = parser.parseSiteMap(contentType, content, sURL);
        }
        long end = System.currentTimeMillis();
        averagedMetrics.update(end - start);

        int linksFound = 0;
        int linksSkippedNotRecentlyModified = 0;

        if (siteMap.isIndex()) {
            SiteMapIndex smi = (SiteMapIndex) siteMap;
            Collection<AbstractSiteMap> subsitemaps = smi.getSitemaps();
            int delay = 0;
            /*
             * keep the subsitemaps as outlinks they will be fetched and parsed
             * in the following steps
             */
            Iterator<AbstractSiteMap> iter = subsitemaps.iterator();
            while (iter.hasNext()) {
                linksFound++;
                AbstractSiteMap asm = iter.next();
                String target = asm.getUrl().toExternalForm();

                Date lastModified = asm.getLastModified();
                if (!recentlyModified(lastModified)) {
                    linksSkippedNotRecentlyModified++;
                    LOG.debug(
                            "{} has a modified date {} which is more than {} hours old",
                            target, lastModified.toString(),
                            filterHoursSinceModified);
                    continue;
                }

                Outlink ol = filterOutlink(sURL, target, parentMetadata,
                        isSitemapKey, "true", isSitemapNewsKey, "false");
                if (ol == null) {
                    continue;
                }

                // add a delay
                if (this.scheduleSitemapsWithDelay > 0) {
                    if (delay > 0) {
                        ol.getMetadata().setValue(
                                DefaultScheduler.DELAY_METADATA,
                                Integer.toString(delay));
                    }
                    delay += this.scheduleSitemapsWithDelay;
                }

                links.add(ol);
                LOG.debug("{} : [sitemap] {}", url, target);
            }
            LOG.info("Sitemap index (found {} sitemaps, {} skipped): {}",
                    linksFound, linksSkippedNotRecentlyModified, url);
        }
        // sitemap files
        else {
            SiteMap sm = (SiteMap) siteMap;
            Collection<SiteMapURL> sitemapURLs = sm.getSiteMapUrls();
            Iterator<SiteMapURL> iter = sitemapURLs.iterator();
            sitemap_urls: while (iter.hasNext()) {
                linksFound++;
                SiteMapURL smurl = iter.next();
                // TODO handle priority in metadata
                double priority = smurl.getPriority();
                // TODO convert the frequency into a numerical value and handle
                // it in metadata
                ChangeFrequency freq = smurl.getChangeFrequency();

                String target = smurl.getUrl().toExternalForm();

                Date lastModified = smurl.getLastModified();
                if (!recentlyModified(lastModified)) {
                    // filter based on the published date
                    linksSkippedNotRecentlyModified++;
                    LOG.debug(
                            "{} has a modified date {} which is more than {} hours old",
                            target, lastModified, filterHoursSinceModified);
                    continue;
                }
                ExtensionMetadata[] newsAttrs = smurl
                        .getAttributesForExtension(Extension.NEWS);
                if (newsAttrs != null) {
                    // filter based on news publication date
                    // <news:publication_date>2008-12-23</news:publication_date>
                    for (ExtensionMetadata attr : newsAttrs) {
                        NewsAttributes newsAttr = (NewsAttributes) attr;
                        Date pubDate = newsAttr.getPublicationDate();
                        if (pubDate != null && !recentlyModified(pubDate)) {
                            linksSkippedNotRecentlyModified++;
                            LOG.debug(
                                    "{} has a news publication date {} which is more than {} hours old",
                                    target, pubDate, filterHoursSinceModified);
                            continue sitemap_urls;
                        }
                    }
                    // TODO: add news attributes to metadata
                }

                // add alternative language links
                ExtensionMetadata[] linkAttrs = smurl
                        .getAttributesForExtension(Extension.LINKS);
                if (linkAttrs != null) {
                    for (ExtensionMetadata attr : linkAttrs) {
                        LinkAttributes linkAttr = (LinkAttributes) attr;
                        URL href = linkAttr.getHref();
                        if (href == null) {
                            continue;
                        }
                        String hrefStr = href.toString();
                        if (hrefStr.equals(target)) {
                            // skip href links duplicating sitemap URL
                            continue;
                        }
                        Outlink ol = filterOutlink(sURL, hrefStr,
                                parentMetadata, isSitemapKey, "false",
                                isSitemapNewsKey, "false");
                        if (ol != null) {
                            links.add(ol);
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
            LOG.info("Sitemap (found {} links, {} skipped): {}", linksFound,
                    linksSkippedNotRecentlyModified, url);
        }

        return siteMap;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void prepare(Map stormConf, TopologyContext context,
            OutputCollector collector) {
        super.prepare(stormConf, context, collector);
        sniffContent = ConfUtils.getBoolean(stormConf,
                "sitemap.sniffContent", false);
        filterHoursSinceModified = ConfUtils.getInt(stormConf,
                "sitemap.filter.hours.since.modified", -1);
        parseFilters = ParseFilters.fromConf(stormConf);
        int maxOffsetGuess = ConfUtils.getInt(stormConf, "sitemap.offset.guess",
                1024);
        contentDetector = new ContentDetector(
                NewsSiteMapParserBolt.contentClues, maxOffsetGuess);
        averagedMetrics = context.registerMetric(
                "news_sitemap_average_processing_time",
                new ReducedMetric(new MeanReducer()), 30);
        scheduleSitemapsWithDelay = ConfUtils.getInt(stormConf,
                "sitemap.schedule.delay", scheduleSitemapsWithDelay);
    }

}
