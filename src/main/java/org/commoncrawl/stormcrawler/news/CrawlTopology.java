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

package org.commoncrawl.stormcrawler.news;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import com.digitalpebble.stormcrawler.warc.FileTimeSizeRotationPolicy;
import com.digitalpebble.stormcrawler.warc.FileTimeSizeRotationPolicy.Units;
import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.digitalpebble.stormcrawler.Constants;
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

        builder.setBolt("sitemap", new NewsSiteMapParserBolt(), numWorkers)
                .setNumTasks(2).localOrShuffleGrouping("fetch");

        builder.setBolt("feed", new FeedParserBolt(), numWorkers).setNumTasks(4)
                .localOrShuffleGrouping("sitemap");

        // don't need to parse the pages but need to update their status
        builder.setBolt("ssb", new DummyIndexer(), numWorkers)
                .localOrShuffleGrouping("feed");

        WARCHdfsBolt warcbolt = getWarcBolt("CC-NEWS");

        // take it from feed default output so that the feed files themselves
        // don't get included - unless we want them too of course!
        builder.setBolt("warc", warcbolt).localOrShuffleGrouping("feed");

        builder.setBolt("status", new StatusUpdaterBolt(), numWorkers)
                .localOrShuffleGrouping("fetch", Constants.StatusStreamName)
                .localOrShuffleGrouping("sitemap", Constants.StatusStreamName)
                .localOrShuffleGrouping("feed", Constants.StatusStreamName)
                .localOrShuffleGrouping("ssb", Constants.StatusStreamName)
                .setNumTasks(numShards);

        return submit(conf, builder);
    }

    protected WARCHdfsBolt getWarcBolt(String filePrefix) {
        // path is absolute
        String warcFilePath = ConfUtils.getString(getConf(), "warc.dir",
                "/data/warc");

        WARCFileNameFormat fileNameFormat = new WARCFileNameFormat();
        fileNameFormat.withPath(warcFilePath);
        fileNameFormat.withPrefix(filePrefix);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("software:", "StormCrawler 1.11 http://stormcrawler.net/");
        fields.put("description", "News crawl for Common Crawl");
        String userAgent = AbstractHttpProtocol.getAgentString(getConf());
        fields.put("http-header-user-agent", userAgent);
        fields.put("http-header-from",
                ConfUtils.getString(getConf(), "http.agent.email"));
        fields.put("format", "WARC File Format 1.0");
        fields.put("conformsTo",
                "http://bibnum.bnf.fr/WARC/WARC_ISO_28500_version1_latestdraft.pdf");

        WARCHdfsBolt warcbolt = (WARCHdfsBolt) new WARCHdfsBolt();
        warcbolt.withConfigKey("warc");
        warcbolt.withFileNameFormat(fileNameFormat);
        warcbolt.withHeader(fields);

        // use RawLocalFileSystem (instead of ChecksumFileSystem) to avoid that
        // WARC files are truncated if the topology is stopped because of a
        // delayed sync of the default ChecksumFileSystem
        Map<String, Object> hdfsConf = new HashMap<>();
        hdfsConf.put("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem");
        getConf().put("warc", hdfsConf);

        // will rotate if reaches size or time limit
        int maxMB = ConfUtils.getInt(getConf(), "warc.rotation.policy.max-mb",
                1024);
        int maxMinutes = ConfUtils.getInt(getConf(),
                "warc.rotation.policy.max-minutes", 1440);
        FileTimeSizeRotationPolicy rotpol = new FileTimeSizeRotationPolicy(
                maxMB, Units.MB);
        rotpol.setTimeRotationInterval(maxMinutes,
                FileTimeSizeRotationPolicy.TimeUnit.MINUTES);
        warcbolt.withRotationPolicy(rotpol);

        return warcbolt;
    }

}