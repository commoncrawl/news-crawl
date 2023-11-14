package org.commoncrawl.stormcrawler.news;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.filtering.URLFilters;
import com.digitalpebble.stormcrawler.persistence.Status;

/**
 * Variant of the URLFilterBolt to go upstream of the fetching to catch anything
 * before it goes further into the topology. If filtered, a URL gets an ERROR
 * status.
 */
public class PreFilterBolt extends BaseRichBolt {

	protected static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private URLFilters urlFilters;

	protected OutputCollector collector;

	private final String filterConfigFile;

	private static final String _s = com.digitalpebble.stormcrawler.Constants.StatusStreamName;

	public PreFilterBolt(String filterConfigFile) {
		this.filterConfigFile = filterConfigFile;
	}

	@Override
	public void execute(Tuple input) {

		// must have at least a URL and metadata
		String urlString = input.getStringByField("url");
		Metadata metadata = (Metadata) input.getValueByField("metadata");

		String filtered = urlFilters.filter(null, null, urlString);
		if (StringUtils.isBlank(filtered)) {
			LOG.debug("URL rejected: {}", urlString);
			// emit with an error to the status stream
			metadata.addValue("error.cause", "Filtered");
			Values v = new Values(urlString, metadata, Status.ERROR);
			collector.emit(_s, input, v);
			collector.ack(input);
			return;
		}

		// pass to std out
		Values v = new Values(urlString, metadata);
		collector.emit(input, v);
		collector.ack(input);
	}

	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declareStream(_s, new Fields("url", "metadata", "status"));
		declarer.declare(new Fields("url", "metadata"));
	}

	@Override
	public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
		this.collector = collector;
		if (filterConfigFile != null) {
			try {
				urlFilters = new URLFilters(stormConf, filterConfigFile);
			} catch (IOException e) {
				throw new RuntimeException("Can't load filters from " + filterConfigFile);
			}
		} else {
			urlFilters = URLFilters.fromConf(stormConf);
		}
	}

}
