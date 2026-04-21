# News Crawler

Crawler for news based on [StormCrawler](https://stormcrawler.apache.org/). Produces WARC files to be stored as part of the [Common Crawl](https://commoncrawl.org/). The data is hosted as [AWS Open Data Set](https://registry.opendata.aws/) – if you want to use the data and not the crawler software please read [the announcement of the news dataset](https://commoncrawl.org/2016/10/news-dataset-available/).


## Prerequisites

* Install OpenSearch 2.19.4
* Install Apache Storm 2.8.4
* Start OpenSearch and Storm
* Create the OpenSearch indices by running [bin/OS_IndexInit.sh](bin/OS_IndexInit.sh) and the dashboards by [OS_ImportDashboards.sh](bin/OS_ImportDashboards.sh)

Alternatively, use the Docker Compose setup, see below.


## Crawler Seeds

The crawler relies on [RSS](https://en.wikipedia.org/wiki/RSS)/[Atom](https://en.wikipedia.org/wiki/Atom_(Web_standard)) feeds and [news sitemaps](https://en.wikipedia.org/wiki/Sitemaps#Google_News_Sitemaps) to find links to news articles on news sites. A small collection of example seeds (feeds and sitemaps) is provided in [./seeds/](./seeds/). Adding support for news sites which do not provide a news feed or sitemap is an open issue, see [#41](https://github.com/commoncrawl/news-crawl/issues/41).


## Configuration

The default configuration should work out-of-the-box. The only thing to do is to configure the user agent properties send in the HTTP request header. Open the file `conf/crawler-conf.yaml` in an editor and fill in the values for `http.agent.name` and all further properties starting with the `http.agent.` prefix.


## Run the crawl

Generate an uberjar:
``` sh
mvn clean package
```

And run ...
``` sh
storm local target/crawler-3.5.1.jar --local-ttl 60 -- org.commoncrawl.stormcrawler.news.CrawlTopology -conf $PWD/conf/opensearch-conf.yaml -conf $PWD/conf/crawler-conf.yaml $PWD/seeds/ feeds.txt
```

This will launch the crawl topology in local mode for 60 seconds. It will also "inject" all URLs found in the file `./seeds/feeds.txt` in the status index. The URLs point to news feeds and sitemaps from which links to news articles are extracted and fetched. The topology will create WARC files in the directory specified in the configuration under the key `warc.dir`. This directory must be created beforehand.

Of course, it's also possible to add (or remove) the seeds (feeds and sitemaps) using the Elasticsearch API. In this case, the can topology can be run without the last two arguments.

Alternatively, the topology can be run from the [crawler.flux](./conf/crawler.flux), please see the [Storm Flux documentation](https://storm.apache.org/releases/2.8.4/flux.html). Make sure to adapt the Flux definition to your needs!

In production, you should use `storm jar ...` to run the topology in distributed mode and continuously (no time limit) including the Storm UI and logging.


## Monitor the crawl

When the topology is running you can check that URLs have been injected and news are getting fetched on <http://localhost:9200/status/_search?pretty>. Or use StormCrawler's OpenSearch dashboards to monitor the crawling process on <http://localhost:5601/>.

There is also a shell script [bin/status](./bin/status) to get aggregated counts from the status index, and to add, delete or force a re-fetch of URLs. E.g., 

```
$> bin/es_status aggregate_status
3824    DISCOVERED
34      FETCHED
5       REDIRECTION
```


## Run Crawl with Docker Compose

Do not forget to create the uberjar (see above) which is included in the Docker image. Simply run:

```
mvn clean package
```

Verify the configuration in the file [docker-compose.yaml](docker-compose.yaml) and [conf/](conf/) is correct:
- Don't forget to adapt the paths to mounted volumes used to persist data (OpenSearch indexes and WARC files).
- Make sure to add the user agent configuration in conf/crawler-conf.yaml.

Then download and build the Docker images:

```
docker compose -f docker-compose.yaml up --build --renew-anon-volumes --remove-orphans
```

Wait until the containers are running, then initialize the OpenSearch index and the dashboards:

```
./bin/OS_IndexInit.sh
./bin/dashboards/OS_ImportDashboards.sh
```

NOTE:
- This will delete existing indexes!
- Make sure that the OpenSearch port 9200 is not already in use or mapped by a running OpenSearch instance. Otherwise OpenSearch commands may affect the running instance!


To launch the topology using [Storm Flux](https://storm.apache.org/releases/2.8.4/flux.html):
```
docker compose run --rm news-crawler \
    storm jar lib/crawler.jar org.apache.storm.flux.Flux --remote /news-crawler/conf/crawler.flux
```
Or using the Java topology:
```
docker compose run --rm news-crawler \
    storm jar lib/crawler.jar -- org.commoncrawl.stormcrawler.news.CrawlTopology \
    /data/seeds '*' -conf conf/opensearch-conf.yaml -conf conf/crawler-conf.yaml
```

After 1-2 minutes if everything is up, connect to OpenSearch on port [9200](http://localhost:9200/) or the OpenSearch dashboards on port [5601](http://localhost:5601/).

For inspecting the worker log files:
```
docker exec storm-supervisor /bin/bash -c 'cat /logs/workers-artifacts/*/*/worker.log'
```

To stop the topology:
```
docker compose run --rm -ti news-crawler /bin/bash

$> storm list
Topology_name        Status     Num_tasks  Num_workers  Uptime_secs  Topology_Id          Owner               
----------------------------------------------------------------------------------------
NewsCrawl            ACTIVE     48         1            146          NewsCrawl-1-1774977605 storm               

$> storm kill NewsCrawl
```

