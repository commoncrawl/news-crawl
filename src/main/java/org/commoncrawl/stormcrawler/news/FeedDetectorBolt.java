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

import java.util.Map;
import org.apache.http.HttpHeaders;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.FeedParserBolt;
import org.apache.stormcrawler.parse.ParseData;
import org.apache.stormcrawler.parse.ParseFilter;
import org.apache.stormcrawler.parse.ParseFilters;
import org.apache.stormcrawler.parse.ParseResult;
import org.apache.stormcrawler.persistence.Status;
import org.slf4j.LoggerFactory;

/** Detect RSS and Atom feeds, but do not parse and extract links */
@SuppressWarnings("serial")
public class FeedDetectorBolt extends FeedParserBolt {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(FeedDetectorBolt.class);

    public static final String[] mimeTypeClues = {"rss+xml", "atom+xml", "text/rss"};

    public static String[][] contentClues = {{"<rss"}, {"<feed"}, {"http://www.w3.org/2005/Atom"}};
    protected static final int maxOffsetContentGuess = 512;
    private static ContentDetector contentDetector =
            new ContentDetector(contentClues, maxOffsetContentGuess);

    private ParseFilter parseFilters;

    @Override
    public void execute(Tuple tuple) {
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");

        boolean isFeed = Boolean.valueOf(metadata.getFirstValue(isFeedKey));

        if (!isFeed) {
            String ct = metadata.getFirstValue(HttpHeaders.CONTENT_TYPE);
            if (ct != null) {
                for (String clue : mimeTypeClues) {
                    if (ct.contains(clue)) {
                        isFeed = true;
                        metadata.setValue(isFeedKey, "true");
                        LOG.info("Feed detected from content type <{}> for {}", ct, url);
                        break;
                    }
                }
            }
        }

        if (!isFeed) {
            if (contentDetector.matches(content)) {
                isFeed = true;
                metadata.setValue(isFeedKey, "true");
                LOG.info("Feed detected from content: {}", url);
            }
        }

        if (isFeed) {
            // do not parse but run parse filters
            ParseResult parse = new ParseResult();
            ParseData parseData = parse.get(url);
            parseData.setMetadata(metadata);
            parseFilters.filter(url, content, null, parse);
            // emit status
            collector.emit(
                    Constants.StatusStreamName, tuple, new Values(url, metadata, Status.FETCHED));
        } else {
            // pass on
            collector.emit(tuple, tuple.getValues());
        }
        collector.ack(tuple);
    }

    @Override
    @SuppressWarnings({"rawtypes"})
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collect) {
        super.prepare(stormConf, context, collect);
        parseFilters = ParseFilters.fromConf(stormConf);
    }
}
