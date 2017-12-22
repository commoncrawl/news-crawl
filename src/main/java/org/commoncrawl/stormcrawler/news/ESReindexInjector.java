package org.commoncrawl.stormcrawler.news;

import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.storm.spout.Scheme;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.bolt.URLFilterBolt;
import com.digitalpebble.stormcrawler.elasticsearch.ESSeedInjector;
import com.digitalpebble.stormcrawler.elasticsearch.persistence.StatusUpdaterBolt;
import com.digitalpebble.stormcrawler.persistence.Scheduler;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.spout.FileSpout;
import com.digitalpebble.stormcrawler.util.StringTabScheme;
import com.esotericsoftware.minlog.Log;

/**
 * Utility class to (re)index status records. Input must be in tabular format:
 * 
 * <pre>
 * URL \t key=value \t key=value \t ...
 * </pre>
 * 
 * The values of the keys &quot;status&quot; and &quot;nextFetchDate&quot; are
 * assigned to these status fields. All other key-value pairss are kept as meta
 * data.
 */
public class ESReindexInjector extends ESSeedInjector {

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(ESReindexInjector.class);

    public static class ReindexStringTabScheme extends StringTabScheme {

        @Override
        public List<Object> deserialize(ByteBuffer bytes) {
            List<Object> res = super.deserialize(bytes);
            Status status = Status.DISCOVERED;
            if (res.size() == 2) {
                Metadata meta = (Metadata) res.get(1);
                String value = meta.getFirstValue("status");
                if (value != null) {
                    status = Status.valueOf(value);
                }
                res.add(status);
            } else {
                LOG.error("Input tuple must have size 2: {}", res.get(0));
            }
            return res;
        }

        @Override
        public Fields getOutputFields() {
            return new Fields("url", "metadata", "status");
        }
    }

    public static class ReindexScheduler extends Scheduler {

        @Override
        protected void init(Map stormConf) {
        }

        @Override
        public Date schedule(Status status, Metadata metadata) {
            String value = metadata.getFirstValue("nextFetchDate");
            Calendar cal = Calendar.getInstance();
            if (value != null) {
                metadata.remove("nextFetchDate");
                try {
                    DateTime date = ISODateTimeFormat.dateTime()
                            .parseDateTime(value);
                    cal.setTime(date.toDate());
                } catch (IllegalArgumentException e) {
                    LOG.error("Failed to parse nextFetchDate {}: {}", value,
                            e.getMessage());
                }
            }
            return cal.getTime();
        }
    }

    @Override
    public int run(String[] args) {

        if (args.length == 0) {
            System.err.println("ESReindexInjector seed_dir file_filter");
            return -1;
        }

        conf.setDebug(false);
        conf.put("scheduler.class", getClass().getName() + "$ReindexScheduler");

        TopologyBuilder builder = new TopologyBuilder();

        Scheme scheme = new ReindexStringTabScheme();

        builder.setSpout("spout", new FileSpout(args[0], args[1], scheme));

        Fields key = new Fields("url");

        builder.setBolt("filter", new URLFilterBolt()).fieldsGrouping("spout",
                key);

        builder.setBolt("enqueue", new StatusUpdaterBolt()).fieldsGrouping(
                "filter", key);

        return submit(getClass().getSimpleName(), conf, builder);
    }

    public static void main(String[] args) throws Exception {
        ConfigurableTopology.start(new ESReindexInjector(), args);
    }

}
