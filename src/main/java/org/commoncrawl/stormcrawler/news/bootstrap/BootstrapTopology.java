/**
 * Licensed to DigitalPebble Ltd under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commoncrawl.stormcrawler.news.bootstrap;

import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.apache.stormcrawler.ConfigurableTopology;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.bolt.FetcherBolt;
import org.apache.stormcrawler.bolt.JSoupParserBolt;
import org.apache.stormcrawler.bolt.URLFilterBolt;
import org.apache.stormcrawler.bolt.URLPartitionerBolt;
import org.apache.stormcrawler.indexing.DummyIndexer;
import org.apache.stormcrawler.opensearch.persistence.AggregationSpout;
import org.apache.stormcrawler.opensearch.persistence.StatusUpdaterBolt;
import org.apache.stormcrawler.spout.FileSpout;
import org.apache.stormcrawler.util.ConfUtils;
import org.apache.stormcrawler.util.URLStreamGrouping;
import org.apache.stormcrawler.warc.WARCHdfsBolt;
import org.commoncrawl.stormcrawler.news.CrawlTopology;
import org.commoncrawl.stormcrawler.news.FeedDetectorBolt;
import org.slf4j.LoggerFactory;

/** Dummy topology to play with the spouts and bolts on ElasticSearch */
public class BootstrapTopology extends CrawlTopology {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(BootstrapTopology.class);

    public static void main(String[] args) throws Exception {
        ConfigurableTopology.start(new BootstrapTopology(), args);
    }

    @Override
    protected int run(String[] args) {
        TopologyBuilder builder = new TopologyBuilder();

        LOG.debug(
                "sitemap.sniffContent: {}",
                ConfUtils.getBoolean(getConf(), "sitemap.sniffContent", false));
        LOG.info(
                "sitemap.sniffContent: {}",
                ConfUtils.getBoolean(getConf(), "sitemap.sniffContent", false));
        LOG.warn(
                "sitemap.sniffContent: {}",
                ConfUtils.getBoolean(getConf(), "sitemap.sniffContent", false));

        int numWorkers = ConfUtils.getInt(getConf(), "topology.workers", 1);

        // set to the real number of shards ONLY if es.status.routing is set to
        // true in the configuration
        int numShards = 16;

        if (args.length >= 2) {
            // arguments include seed directory and file pattern
            LOG.info("Injecting seeds from {} by pattern {}", args[0], args[1]);
            builder.setSpout("filespout", new FileSpout(args[0], args[1], true));
            Fields key = new Fields("url");

            builder.setBolt("filter", new URLFilterBolt())
                    .fieldsGrouping("filespout", Constants.StatusStreamName, key);
        }

        builder.setSpout("spout", new AggregationSpout(), numShards);

        builder.setBolt("partitioner", new URLPartitionerBolt(), numWorkers)
                .shuffleGrouping("spout");

        builder.setBolt("fetch", new FetcherBolt(), numWorkers)
                .fieldsGrouping("partitioner", new Fields("key"));

        builder.setBolt("sitemap", new NewsSiteMapDetectorBolt(), numWorkers)
                .localOrShuffleGrouping("fetch");

        builder.setBolt("feed", new FeedDetectorBolt(), numWorkers)
                .localOrShuffleGrouping("sitemap");

        builder.setBolt("parse", new JSoupParserBolt()).localOrShuffleGrouping("feed");

        // don't need to parse the pages but need to update their status
        builder.setBolt("ssb", new DummyIndexer(), numWorkers).localOrShuffleGrouping("parse");

        WARCHdfsBolt warcbolt = getWarcBolt("CC-NEWS-BOOTSTRAP");

        builder.setBolt("warc", warcbolt).localOrShuffleGrouping("fetch");

        builder.setBolt("status", new StatusUpdaterBolt(), numWorkers)
                .localOrShuffleGrouping("fetch", Constants.StatusStreamName)
                .localOrShuffleGrouping("sitemap", Constants.StatusStreamName)
                .localOrShuffleGrouping("feed", Constants.StatusStreamName)
                .localOrShuffleGrouping("parse", Constants.StatusStreamName)
                .localOrShuffleGrouping("ssb", Constants.StatusStreamName)
                .setNumTasks(numShards)
                .customGrouping("filter", Constants.StatusStreamName, new URLStreamGrouping());

        return submit(conf, builder);
    }
}
