/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import com.digitalpebble.stormcrawler.FileTimeSizeRotationPolicy.Units;
import com.digitalpebble.stormcrawler.bolt.FeedParserBolt;
import com.digitalpebble.stormcrawler.bolt.FetcherBolt;
import com.digitalpebble.stormcrawler.bolt.URLPartitionerBolt;
import com.digitalpebble.stormcrawler.elasticsearch.persistence.AggregationSpout;
import com.digitalpebble.stormcrawler.elasticsearch.persistence.StatusUpdaterBolt;
import com.digitalpebble.stormcrawler.indexing.DummyIndexer;
import com.digitalpebble.stormcrawler.protocol.AbstractHttpProtocol;
import com.digitalpebble.stormcrawler.util.ConfUtils;
import com.digitalpebble.stormcrawler.warc.WARCFileNameFormat;
import com.digitalpebble.stormcrawler.warc.WARCHdfsBolt;

/**
 * Dummy topology to play with the spouts and bolts on ElasticSearch
 */
public class CrawlTopology extends ConfigurableTopology {

    public static void main(String[] args) throws Exception {
        ConfigurableTopology.start(new CrawlTopology(), args);
    }

    @Override
    protected int run(String[] args) {
        TopologyBuilder builder = new TopologyBuilder();

        int numWorkers = ConfUtils.getInt(getConf(), "topology.workers", 1);

        // set to the real number of shards ONLY if es.status.routing is set to
        // true in the configuration
        int numShards = 10;

        builder.setSpout("spout", new AggregationSpout(), numShards);

        builder.setBolt("partitioner", new URLPartitionerBolt(), numWorkers)
                .shuffleGrouping("spout");

        builder.setBolt("fetch", new FetcherBolt(), numWorkers)
                .fieldsGrouping("partitioner", new Fields("key"));

        builder.setBolt("feed", new FeedParserBolt(), numWorkers)
                .localOrShuffleGrouping("fetch");

        // don't need to parse the pages but need to update their status
        builder.setBolt("ssb", new DummyIndexer(), numWorkers)
                .localOrShuffleGrouping("feed");

        // path is absolute
        String warcFilePath = ConfUtils.getString(getConf(), "warc.dir",
                "/data/warc");

        WARCFileNameFormat fileNameFormat = new WARCFileNameFormat();
        fileNameFormat.withPath(warcFilePath);
        fileNameFormat.withPrefix("CC-NEWS");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("software:", "StormCrawler 1.1.1 http://stormcrawler.net/");
        fields.put("description", "News crawl for CommonCrawl");
        String userAgent = AbstractHttpProtocol.getAgentString(getConf());
        fields.put("http-header-user-agent", userAgent);
        fields.put("http-header-from",
                ConfUtils.getString(getConf(), "http.agent.email"));
        fields.put("format", "WARC File Format 1.0");
        fields.put("conformsTo",
                "http://bibnum.bnf.fr/WARC/WARC_ISO_28500_version1_latestdraft.pdf");

        WARCHdfsBolt warcbolt = (WARCHdfsBolt) new WARCHdfsBolt()
                .withFileNameFormat(fileNameFormat);
        warcbolt.withHeader(fields);

        // will rotate if reaches 1GB or N units of time
        FileTimeSizeRotationPolicy rotpol = new FileTimeSizeRotationPolicy(1.0f,
                Units.GB);
        rotpol.setTimeRotationInterval(1,
                FileTimeSizeRotationPolicy.TimeUnit.DAYS);
        warcbolt.withRotationPolicy(rotpol);

        // take it from feed default output so that the feed files themselves
        // don't get included - unless we want them too of course!
        builder.setBolt("warc", warcbolt).localOrShuffleGrouping("feed");

        builder.setBolt("status", new StatusUpdaterBolt(), numWorkers)
                .localOrShuffleGrouping("fetch", Constants.StatusStreamName)
                .localOrShuffleGrouping("feed", Constants.StatusStreamName)
                .localOrShuffleGrouping("ssb", Constants.StatusStreamName)
                .setNumTasks(numShards);

        return submit(conf, builder);
    }
}