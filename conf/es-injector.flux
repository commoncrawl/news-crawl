name: "injector"

includes:
    - resource: true
      file: "/crawler-default.yaml"
      override: false

    - resource: false
      file: "conf/es-conf.yaml"
      override: true

    - resource: false
      file: "conf/crawler-conf.yaml"
      override: true

spouts:
  - id: "spout"
    className: "com.digitalpebble.stormcrawler.spout.FileSpout"
    parallelism: 1
    constructorArgs:
      - "seeds"
      - "feeds.txt"
      - true

bolts:
  - id: "filter"
    className: "com.digitalpebble.stormcrawler.bolt.URLFilterBolt"
    parallelism: 1
  - id: "status"
    className: "com.digitalpebble.stormcrawler.elasticsearch.persistence.StatusUpdaterBolt"
    parallelism: 1

streams:
  - from: "spout"
    to: "filter"
    grouping:
      type: FIELDS
      args: ["url"]
      streamId: "status"
  - from: "filter"
    to: "status"
    grouping:
      type: CUSTOM
      customClass:
        className: "com.digitalpebble.stormcrawler.util.URLStreamGrouping"
        constructorArgs:
          - "byDomain"
      streamId: "status"