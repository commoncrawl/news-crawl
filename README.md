# NEWS-CRAWL

Crawler for news based on [StormCrawler](https://stormcrawler.net/). Produces WARC files to be stored as part of the [Common Crawl](https://commoncrawl.org/). The data is hosted as [AWS Open Data Set](https://registry.opendata.aws/) – if you want to use the data and not the crawler software please read [the announcement of the news dataset](https://commoncrawl.org/2016/10/news-dataset-available/).


Prerequisites
-------------

* Install Elasticsearch 7.5.0 (ev. also Kibana)
* Install Apache Storm 1.2.3
* Start Elasticsearch and Storm
* Build ES indices by running `bin/ES_IndexInit.sh`

Crawler Seeds
-------------

The crawler relies on [RSS](https://en.wikipedia.org/wiki/RSS)/[Atom](https://en.wikipedia.org/wiki/Atom_(Web_standard)) feeds and [news sitemaps](https://en.wikipedia.org/wiki/Sitemaps#Google_News_Sitemaps) to find links to news articles on news sites. A small collection of example seeds (feeds and sitemaps) is provided in [./seeds/](./seeds/). Adding support for news sites which do not provide a news feed or sitemap is an open issue, see [#41](//github.com/commoncrawl/news-crawl/issues/41).


Configuration
-------------

The default configuration should work out-of-the-box. The only thing to do is to configure the user agent properties send in the HTTP request header. Open the file `conf/crawler-conf.yaml` in an editor and fill in the values for `http.agent.name` and all further properties starting with the `http.agent.` prefix.


Run the crawl
-------------

Generate an uberjar:
``` sh
mvn clean package
```

And run ...
``` sh
storm jar target/crawler-1.18.jar org.commoncrawl.stormcrawler.news.CrawlTopology -conf $PWD/conf/es-conf.yaml -conf $PWD/conf/crawler-conf.yaml $PWD/seeds/ feeds.txt
```

This will launch the crawl topology. It will also "inject" all URLs found in the file `./seeds/feeds.txt` in the status index. The URLs point to news feeds and sitemaps from which links to news articles are extracted and fetched. The topology will create WARC files in the directory specified in the configuration under the key `warc.dir`. This directory must be created beforehand.

Of course, it's also possible to add (or remove) the seeds (feeds and sitemaps) using the Elasticsearch API. In this case, the can topology can be run without the last two arguments.

Alternatively, the topology can be run from the [crawler.flux](./conf/crawler.flux), please see the [Storm Flux documentation](https://storm.apache.org/releases/1.2.3/flux.html). Make sure to adapt the Flux definition to your needs!


Monitor the crawl
-----------------

When the topology is running you can check that URLs have been injected and news are getting fetched on [http://localhost:9200/status/_search?pretty]. Or use StormCrawler's Kibana dashboards to monitor the crawling process. Please follow the instructions to install the templates for Kibana provided as part of [StormCrawler's Elasticsearch module documentation](//github.com/DigitalPebble/storm-crawler/tree/master/external/elasticsearch).

There is also a shell script [bin/es_status](./bin/es_status) to get aggregated counts from the status index, and to add, delete or force a re-fetch of URLs. E.g., 
```
$> bin/es_status aggregate_status
3824    DISCOVERED
34      FETCHED
5       REDIRECTION
```


Run Crawl from Docker Container
-------------------------------

First, download Apache Storm 1.2.3. from the [download page](https://storm.apache.org/downloads.html) and place it in the directory `downloads`:
```
STORM_VERSION=1.2.3
mkdir downloads
wget -q -P downloads --timestamping http://www-us.apache.org/dist/storm/apache-storm-$STORM_VERSION/apache-storm-$STORM_VERSION.tar.gz
```

Do not forget to create the uberjar (see above) which is included in the Docker image. Simply run:
```
mvn clean package
```

Then build the Docker image from the [Dockerfile](./Dockerfile):
```
docker build -t newscrawler:1.18 .
```

To launch an interactive container:
```
docker run --net=host \
    -p 127.0.0.1:9200:9200 \
    -p 5601:5601 -p 8080:8080 \
    -v .../newscrawl/elasticsearch:/data/elasticsearch \
    -v .../newscrawl/warc:/data/warc \
    --rm -i -t newscrawler:1.18 /bin/bash
```

NOTE: don't forget to adapt the paths to mounted volumes used to persist data on the host.

CAVEAT: Make sure that the Elasticsearch port 9200 is not already in use or mapped by a running ES instance. Otherwise Elasticsearch commands may affect the running instance!

The crawler is launched in the running container by the script
```
/home/ubuntu/news-crawler/bin/run-crawler.sh
```

After 1-2 minutes if everything is up, connect to Elasticsearch on port [9200](http://127.0.0.1:9200/) or Kibana on port [5601](http://127.0.0.1:5601/).
