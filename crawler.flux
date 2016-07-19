name: "feeds"

includes:
    - resource: true
      file: "/crawler-default.yaml"
      override: false

    - resource: false
      file: "crawl-conf.yaml"
      override: true

    - resource: false
      file: "es-conf.yaml"
      override: true

# specific to feeds processing
config:
  parser.emitOutlinks: false
  fetchInterval.isFeed=true: 10
  # revisit a page with a fetch error after 2 hours (value in minutes)
  fetchInterval.fetch.error: 120

spouts:
  - id: "spout"
    className: "com.digitalpebble.storm.crawler.elasticsearch.persistence.AggregationSpout"
    parallelism: 16

bolts:
  - id: "partitioner"
    className: "com.digitalpebble.storm.crawler.bolt.URLPartitionerBolt"
    parallelism: 1
  - id: "fetcher"
    className: "com.digitalpebble.storm.crawler.bolt.FetcherBolt"
    parallelism: 1
  - id: "feed"
    className: "com.digitalpebble.storm.crawler.bolt.FeedParserBolt"
    parallelism: 1
  - id: "parse"
    className: "com.digitalpebble.storm.crawler.bolt.JSoupParserBolt"
    parallelism: 1
  - id: "index"
    className: "com.digitalpebble.storm.crawler.elasticsearch.bolt.IndexerBolt"
    parallelism: 1
  - id: "status"
    className: "com.digitalpebble.storm.crawler.elasticsearch.persistence.StatusUpdaterBolt"
    parallelism: 1

streams:
  - from: "spout"
    to: "partitioner"
    grouping:
      type: SHUFFLE

  - from: "partitioner"
    to: "fetcher"
    grouping:
      type: FIELDS
      args: ["key"]

  - from: "fetcher"
    to: "feed"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "feed"
    to: "parse"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "parse"
    to: "index"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "fetcher"
    to: "status"
    grouping:
      type: LOCAL_OR_SHUFFLE
      streamId: "status"

  - from: "feed"
    to: "status"
    grouping:
      type: LOCAL_OR_SHUFFLE
      streamId: "status"

  - from: "parse"
    to: "status"
    grouping:
      type: LOCAL_OR_SHUFFLE
      streamId: "status"

  - from: "index"
    to: "status"
    grouping:
      type: LOCAL_OR_SHUFFLE
      streamId: "status"
