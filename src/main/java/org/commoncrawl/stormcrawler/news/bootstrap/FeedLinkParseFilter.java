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

import java.util.ArrayList;

import org.slf4j.LoggerFactory;
import org.w3c.dom.DocumentFragment;

import com.digitalpebble.stormcrawler.bolt.FeedParserBolt;
import com.digitalpebble.stormcrawler.parse.Outlink;
import com.digitalpebble.stormcrawler.parse.ParseResult;
import com.digitalpebble.stormcrawler.parse.filter.LinkParseFilter;

/**
 * ParseFilter which extracts exclusively RSS links via Xpath, all other links
 * are skipped. See {@link LinkParseFilter} how to register and configure in
 * parsefilters.json. A configuration snippet:
 * <pre>
 *     {
 *      "class": "org.commoncrawl.stormcrawler.news.bootstrap.FeedLinkParseFilter",
 *      "name": "FeedLinks",
 *      "params": {
 *        "rss1": "//LINK[@rel='alternate' and @type='application/rss+xml']/@href",
 *        "rss2": "//LINK[@rel='alternate' and @type='text/rss']/@href",
 *        "atom": "//LINK[@rel='alternate' and @type='application/atom+xml']/@href"
 *      }
 *    }
 * </pre>
 */
public class FeedLinkParseFilter extends LinkParseFilter {

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(FeedLinkParseFilter.class);

    @Override
    public void filter(String URL, byte[] content, DocumentFragment doc,
            ParseResult parse) {

        // skip existing links
        logLinks(parse, URL, "Skipped links");
        parse.setOutlinks(new ArrayList<Outlink>());

        super.filter(URL, content, doc, parse);
        for (Outlink outlink : parse.getOutlinks())
            outlink.getMetadata().addValue(FeedParserBolt.isFeedKey, "true");
        logLinks(parse, URL, "Added links");
    }

    public static void logLinks(ParseResult parse, String URL, String message) {
        if (LOG.isDebugEnabled() && parse.getOutlinks().size() > 0) {
            if (!message.isEmpty())
                LOG.debug("{} for {}:", message, URL);
            for (Outlink outlink : parse.getOutlinks())
                LOG.debug(outlink.getTargetURL());
        }
    }

}