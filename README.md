# NEWS-CRAWL

Crawler for news feeds based on [StormCrawler](http://stormcrawler.net). Produces WARC files to be stored as part of the [Common Crawl](https://commoncrawl.org/). The data is hosted as [AWS Open Data Set](https://registry.opendata.aws/) – if you want to use the data and not the crawler software please read [the announcement of the news dataset](https://commoncrawl.org/2016/10/news-dataset-available/).


Prerequisites
------------

* Install Elasticsearch 7.0.0 (ev. also Kibana)
* Install Apache Storm 1.2.2
* Clone and compile [StormCrawler](https://github.com/DigitalPebble/storm-crawler) with `mvn clean install`
* Start Elasticsearch and Storm
* Build ES indices by running `bin/ES_IndexInit.sh`


Configuration
------------

The default configuration should work out-of-the-box. The only thing to do is to configure the user agent properties send in the HTTP request header. Open the file `conf/crawler-conf.yaml` in an editor and fill in the values for `http.agent.name` and all further properties starting with the `http.agent.` prefix.


Run the crawl
------------

Generate an uberjar:
``` sh
mvn clean package
```

Inject some URLs with 

``` sh
storm jar target/crawler-1.14.jar com.digitalpebble.stormcrawler.elasticsearch.ESSeedInjector . seeds/feeds.txt -conf conf/es-conf.yaml -conf conf/crawler-conf.yaml
```

This pushes the newsfeed seeds to the status index and has to be done every time new seeds are added. To delete seeds, delete by query in the ES index or wipe the index clean and reindex the whole lot.

You can check that the URLs have been injected on [http://localhost:9200/status/_search?pretty].

You can then run the crawl topology with :

``` sh
storm jar target/crawler-1.14.jar org.commoncrawl.stormcrawler.news.CrawlTopology -conf conf/es-conf.yaml -conf conf/crawler-conf.yaml
```

The topology will create WARC files in the directory specified in the configuration under the key `warc.dir`. This directory must be created beforehand.


Monitor the crawl
------------

See instructions on [https://github.com/DigitalPebble/storm-crawler/tree/master/external/elasticsearch] to install the templates for Kibana. 


Run Crawl from Docker Container
-------------

First, download Apache Storm 1.2.2. from the [download page](http://storm.apache.org/downloads.html) and place it in the directory `downloads`:
```
STORM_VERSION=1.2.2
mkdir downloads
wget -q -P downloads --timestamping http://www-us.apache.org/dist/storm/apache-storm-$STORM_VERSION/apache-storm-$STORM_VERSION.tar.gz
```

Then build the Docker image from the [Dockerfile](./Dockerfile):
```
docker build -t newscrawler:1.14 .
```

Note: the uberjar is included in the Docker image and needs to be built first (see above).

Launch an interactive container:
```
docker run --net=host \
    -p 127.0.0.1:9200:9200 \
    -p 5601:5601 -p 8080:8080 \
    -v .../newscrawl/elasticsearch:/data/elasticsearch \
    -v .../newscrawl/warc:/data/warc \
    --rm -i -t newscrawler:1.14 /bin/bash
```

NOTE: don't forget to adapt the paths to mounted volumes used to persist data on the host.

CAVEAT: Make sure that the Elasticsearch port 9200 is not already in use or mapped by a running ES instance. Otherwise ES commands may affect the running instance!

The crawler is launched in the running container by the script
```
/home/ubuntu/news-crawler/bin/run-crawler.sh
```

After 1-2 minutes if everything is up, connect to Elasticsearch on port [9200](http://127.0.0.1:9200/) or Kibana on port [5601](http://127.0.0.1:5601/).
