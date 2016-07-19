NEWS-CRAWL

Crawler for news feeds based on [StormCrawler](http://stormcrawler.net). Produces WARC files to be stored as part of the CommonCrawl dataset.

Prerequisites
------------

* Install ElasticSearch 2.3.1 and Kibana 4.5.1
* Install Apache Storm 1.0
* Clone and compile [https://github.com/DigitalPebble/sc-warc]
* Start ES and Storm
* Build ES indices with : `curl -L "https://git.io/vaGkv" | bash`

Run the crawl
------------

Generate an uberjar:
``` sh
mvn clean package
```

Inject some URLs with 

``` sh
storm jar target/crawler-1.0-SNAPSHOT.jar com.digitalpebble.stormcrawler.elasticsearch.ESSeedInjector . feeds -conf es-conf.yaml -conf crawler-conf.yaml -local
```

This pushes the newsfeed seeds to the status index and has to be done every time new seeds are added. To delete seeds, delete by query in the ES index or wipe the index clean and reindex the whole lot.

You can check that the URLs have been injected on [http://localhost:9200/status/_search?pretty].

You can then run the crawl topology with :

``` sh
storm jar target/crawler-1.0-SNAPSHOT.jar com.digitalpebble.stormcrawler.CrawlTopology -conf es-conf.yaml -conf crawler-conf.yaml
```

