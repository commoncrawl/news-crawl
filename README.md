# NEWS-CRAWL

Crawler for news feeds based on [StormCrawler](http://stormcrawler.net). Produces WARC files to be stored as part of the CommonCrawl dataset.

Prerequisites
------------

* Install ElasticSearch 2.3.1 and Kibana 4.5.1
* Install Apache Storm 1.0
* Clone and compile [https://github.com/DigitalPebble/sc-warc] with `mvn clean install`
* Clone and compile [https://github.com/DigitalPebble/storm-crawler] with `mvn clean install`
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

The topology will create WARC files in the directory specified in the configuration under the key `warc.dir`. This directory must be created beforehand.

Monitor the crawl
------------

See instructions on [https://github.com/DigitalPebble/storm-crawler/tree/master/external/elasticsearch] to install the templates for Kibana. 

Run Crawl from Docker Container
-------------

Build the Docker image from the [Dockerfile](./Dockerfile):
```
docker build -t newscrawler:1.0 .
```

Launch an interactive container:
```
docker run --net=host \
    -p 127.0.0.1:9200:9200 -p 127.0.0.1:9300:9300 \
    -p 5601:5601 -p 8080:8080 \
    -v .../newscrawl/elasticsearch:/data/elasticsearch \
    -v .../newscrawl/warc:/data/warc \
    --rm -i -t newscrawler:1.0 /bin/bash
```

Note: don't forget to adapt the paths to mounted volumes used to persist data on the host.

The crawler is launched in the running container by the script
```
/home/ubuntu/news-crawler/bin/run-crawler.sh
```

After 1-2 minutes if everything is up, connect to Elasticsearch on port [9200](http://127.0.0.1:9200/) or Kibana on port [5601](http://127.0.0.1:5601/).
