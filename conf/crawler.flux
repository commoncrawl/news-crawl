name: "NewsCrawl"

includes:
    - resource: true
      file: "/crawler-default.yaml"
      override: false

    - resource: false
      file: "conf/crawler-conf.yaml"
      override: true

    - resource: false
      file: "conf/opensearch-conf.yaml"
      override: true

config:
  # use RawLocalFileSystem (instead of ChecksumFileSystem) to avoid that
  # WARC files are truncated if the topology is stopped because of a
  # delayed sync of the default ChecksumFileSystem
  warc: {"fs.file.impl": "org.apache.hadoop.fs.RawLocalFileSystem"}

  # Allow/disallow cross submission of sitemaps
  crossSubmit.allowed: false

  # use lenient:true to compare only domains in the submitted sitemaps
  crossSubmit.lenient: true

components:
  - id: "WARCFileNameFormat"
    className: "org.apache.stormcrawler.warc.WARCFileNameFormat"
    configMethods:
      - name: "withPath"
        args:
          - "/data/warc"
      - name: "withPrefix"
        args:
          - "CC-NEWS"
  - id: "WARCFileRotationPolicy"
    className: "org.apache.stormcrawler.warc.FileTimeSizeRotationPolicy"
    constructorArgs:
      - 1024
      - MB
    configMethods:
      - name: "setTimeRotationInterval"
        args:
          - 1440
          - MINUTES
  - id: "WARCInfo"
    className: "java.util.LinkedHashMap"
    configMethods:
      - name: "put"
        args:
         - "software"
         - "StormCrawler 3.6.0 https://stormcrawler.apache.org/"
      - name: "put"
        args:
         - "description"
         - "News crawl for Common Crawl"
      - name: "put"
        args:
         - "http-header-user-agent"
         - "... Please insert your user-agent name"
      - name: "put"
        args:
         - "http-header-from"
         - "..."
      - name: "put"
        args:
         - "operator"
         - "..."
      - name: "put"
        args:
         - "robots"
         - "..."
      - name: "put"
        args:
         - "format"
         - "WARC File Format 1.1"
      - name: "put"
        args:
         - "conformsTo"
         - "https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/"

spouts:
  - id: "spout"
    className: "org.apache.stormcrawler.opensearch.persistence.AggregationSpout"
    parallelism: 16
  - id: "filespout"
    className: "org.apache.stormcrawler.spout.FileSpout"
    parallelism: 1
    constructorArgs:
      - "/data/seeds/"
      - "feeds.txt"
      - true

bolts:
  - id: "filter"
    className: "org.apache.stormcrawler.bolt.URLFilterBolt"
    parallelism: 1
  - id: "prefilter"
    className: "org.commoncrawl.stormcrawler.news.PreFilterBolt"
    parallelism: 1
    constructorArgs:
      - "pre-urlfilters.json"
  - id: "partitioner"
    className: "org.apache.stormcrawler.bolt.URLPartitionerBolt"
    parallelism: 1
  - id: "fetcher"
    className: "org.apache.stormcrawler.bolt.FetcherBolt"
    parallelism: 1
  - id: "sitemap"
    className: "org.commoncrawl.stormcrawler.news.NewsSiteMapParserBolt"
    parallelism: 1
  - id: "feed"
    className: "org.apache.stormcrawler.bolt.FeedParserBolt"
    parallelism: 1
  - id: "ssbolt"
    className: "org.apache.stormcrawler.indexing.DummyIndexer"
    parallelism: 1
  - id: "warc"
    className: "org.apache.stormcrawler.warc.WARCHdfsBolt"
    parallelism: 1
    configMethods:
      - name: "withFileNameFormat"
        args:
          - ref: "WARCFileNameFormat"
      - name: "withRotationPolicy"
        args:
          - ref: "WARCFileRotationPolicy"
      - name: "withRequestRecords"
      - name: "withHeader"
        args:
          - ref: "WARCInfo"
      - name: "withConfigKey"
        args:
          - "warc"
  - id: "status"
    className: "org.apache.stormcrawler.opensearch.persistence.StatusUpdaterBolt"
    parallelism: 1

streams:
  - from: "spout"
    to: "prefilter"
    grouping:
      type: SHUFFLE

  - from: "prefilter"
    to: "partitioner"
    grouping:
      type: SHUFFLE

  - from: "partitioner"
    to: "fetcher"
    grouping:
      type: FIELDS
      args: ["key"]

  - from: "fetcher"
    to: "sitemap"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "sitemap"
    to: "feed"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "feed"
    to: "warc"
    grouping:
      type: LOCAL_OR_SHUFFLE

  - from: "feed"
    to: "ssbolt"
    grouping:
      type: LOCAL_OR_SHUFFLE
      
  - from: "prefilter"
    to: "status"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  - from: "fetcher"
    to: "status"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  - from: "sitemap"
    to: "status"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  - from: "feed"
    to: "status"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  - from: "ssbolt"
    to: "status"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  # part of the topology used to inject seeds

  - from: "filespout"
    to: "filter"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"

  - from: "filter"
    to: "status"
    grouping:
      streamId: "status"
      type: CUSTOM
      customClass:
        className: "org.apache.stormcrawler.util.URLStreamGrouping"
        constructorArgs:
          - "byDomain"

