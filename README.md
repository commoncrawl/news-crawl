# News Crawler

Crawler for news based on [StormCrawler](https://stormcrawler.apache.org/). Produces WARC files to be stored as part of the [Common Crawl](https://commoncrawl.org/). The data is hosted as [AWS Open Data Set](https://registry.opendata.aws/) – if you want to use the data and not the crawler software please read [the announcement of the news dataset](https://commoncrawl.org/2016/10/news-dataset-available/).


## How it works

The project is a custom [Apache StormCrawler](https://stormcrawler.apache.org/) topology. Stock StormCrawler provides the heavy machinery — the HTTP fetcher, robots.txt handling, URL partitioner, WARC writer and OpenSearch status index. This repository adds ~10 **news-specific** bolts and filters on top (parsing Google News sitemaps, detecting RSS/Atom feeds, news-aware URL filtering).

Two indexes / stores are involved:

- **OpenSearch** holds the *URL status index*: every known URL and its state (`DISCOVERED`, `FETCHED`, `REDIRECTION`, `ERROR`). This is the crawler's memory of what to fetch next.
- **WARC files** on local disk (`warc.dir`) hold the actual fetched content — this is the output shipped to Common Crawl.

The production crawl is a Storm pipeline defined primarily in [`conf/crawler.flux`](conf/crawler.flux) (the main topology, launched via [Storm Flux](https://storm.apache.org/releases/2.8.8/flux.html)). A functionally-equivalent Java implementation of the same pipeline is provided in [`CrawlTopology`](src/main/java/org/commoncrawl/stormcrawler/news/CrawlTopology.java). Either way the DAG is the same:

```
seeds/feeds.txt
  → FileSpout → PreFilterBolt → URLPartitionerBolt → FetcherBolt
  → {NewsSiteMapParserBolt, FeedParserBolt}          (extract article links)
  → WARCHdfsBolt (write content to WARC on disk)
  + StatusUpdaterBolt (write URL states to OpenSearch)
```

Feeds and sitemaps are re-fetched on a schedule to discover new articles; the article URLs they yield are queued in the status index and fetched in turn.


## Prerequisites
* JVM 17 or higher
* Install OpenSearch 2.19.5
* Install Apache Storm 2.8.8
* Start OpenSearch and Storm
* Create the OpenSearch indices by running [bin/OS_IndexInit.sh](bin/OS_IndexInit.sh) and the dashboards by [OS_ImportDashboards.sh](bin/OS_ImportDashboards.sh)

Alternatively, use the Docker Compose setup, see below.


## Crawler Seeds

The crawler relies on [RSS](https://en.wikipedia.org/wiki/RSS)/[Atom](https://en.wikipedia.org/wiki/Atom_(Web_standard)) feeds and [news sitemaps](https://en.wikipedia.org/wiki/Sitemaps#Google_News_Sitemaps) to find links to news articles on news sites. A small collection of example seeds (feeds and sitemaps) is provided in [./seeds/](./seeds/). Adding support for news sites which do not provide a news feed or sitemap is an open issue, see [#41](https://github.com/commoncrawl/news-crawl/issues/41).


## Configuration

The default configuration works out-of-the-box for a local test crawl. The **only mandatory change** is the HTTP user agent — news sites reject requests without a real `User-Agent`. Open `conf/crawler-conf.yaml` and fill in `http.agent.name` and the other `http.agent.*` properties (`version`, `url`, `email`).

The crawl is configured through a handful of files under [`conf/`](./conf/), layered on top of StormCrawler's built-in `crawler-default.yaml` (later files override earlier ones):

| File | What it configures |
| --- | --- |
| [`crawler-conf.yaml`](conf/crawler-conf.yaml) | Main crawler config: HTTP agent (`http.agent.*`), fetch parallelism, timeouts, content-size limits, and the `warc.*` keys (`warc.dir`, `warc.rotation.policy.max-mb` / `max-minutes`). |
| [`opensearch-conf.yaml`](conf/opensearch-conf.yaml) | OpenSearch cluster addresses and the status-index / indexer settings. |
| [`crawler.flux`](conf/crawler.flux) | The main topology definition itself (spouts, bolts, streams) **and** the WARC output settings — see below. Includes the two files above. |
| [`bootstrap-conf.yaml`](conf/bootstrap-conf.yaml) | Config for the [bootstrap topology](#bootstrap-discovering-feeds-and-sitemaps); sets a distinct WARC directory (`/data/warc/bootstrap`) and a more conservative `fetcher.server.delay`. |

### WARC output settings

Where WARC files are written and how they rotate is configured **differently** depending on which topology you launch:

- **Flux (`crawler.flux`) — hardcoded in the component definitions.** The output path is `WARCFileNameFormat.withPath("/data/warc")` and rotation is `1024 MB` / `1440 minutes` in `WARCFileRotationPolicy`. These **ignore** the `warc.*` config keys, so to change them you must edit `crawler.flux` directly. Also fill in the placeholder `WARCInfo` header values (`http-header-user-agent`, `http-header-from`, `operator`, `robots`), which ship as `"..."`.
- **Java (`CrawlTopology`) — read from config.** The output path comes from `warc.dir` (default `/data/warc`) and rotation from `warc.rotation.policy.max-mb` / `warc.rotation.policy.max-minutes` in `crawler-conf.yaml`; the WARC header fields are derived from the `http.agent.*` values at runtime.

In both cases the WARC output directory must exist before you start the crawl.


## Run the crawl

Once the configuration is in place, generate the uberjar:
``` sh
mvn clean package
```

The main news crawler topology is defined in [`conf/crawler.flux`](./conf/crawler.flux) and launched via [Storm Flux](https://storm.apache.org/releases/2.8.8/flux.html) — this is also the default entry point of the shaded jar (its manifest `mainClass` is `org.apache.storm.flux.Flux`).

For a quick local smoke test you can instead run the equivalent Java topology [`CrawlTopology`](src/main/java/org/commoncrawl/stormcrawler/news/CrawlTopology.java) in local mode. Note the `--` separator: it overrides the manifest's default Flux main class so the named class runs instead.
``` sh
storm local target/crawler-3.6.0.jar --local-ttl 60 -- org.commoncrawl.stormcrawler.news.CrawlTopology -conf $PWD/conf/opensearch-conf.yaml -conf $PWD/conf/crawler-conf.yaml $PWD/seeds/ feeds.txt
```

This will launch the crawl topology in local mode for 60 seconds. It will also "inject" all URLs found in the file `./seeds/feeds.txt` in the status index. The URLs point to news feeds and sitemaps from which links to news articles are extracted and fetched. The topology will create WARC files in the directory specified in the configuration under the key `warc.dir`. This directory must be created beforehand.

Of course, it's also possible to add (or remove) the seeds (feeds and sitemaps) using the OpenSearch API. In this case, the topology can be run without the last two arguments.

In production, you should use `storm jar ...` to run the topology in distributed mode and continuously (no time limit) including the Storm UI and logging (see the Docker Compose section below for the exact Flux and Java commands).


## Bootstrap: discovering feeds and sitemaps

The news crawl assumes its seeds are *already known* to be feeds or news sitemaps. When you only have a list of candidate URLs (e.g. news site home pages) and don't yet know which of them expose a feed or a Google News sitemap, run the **bootstrap topology** first.

[`BootstrapTopology`](src/main/java/org/commoncrawl/stormcrawler/news/bootstrap/BootstrapTopology.java) reuses the same fetch skeleton but replaces the *parser* bolts with *detector* bolts — [`NewsSiteMapDetectorBolt`](src/main/java/org/commoncrawl/stormcrawler/news/bootstrap/NewsSiteMapDetectorBolt.java) and [`FeedDetectorBolt`](src/main/java/org/commoncrawl/stormcrawler/news/FeedDetectorBolt.java) — which classify a fetched URL by its content/`Content-Type` instead of extracting article links from it. The detected feeds and sitemaps can then be used as seeds for the production crawl above.

It uses its own configuration ([conf/bootstrap-conf.yaml](conf/bootstrap-conf.yaml)), which sets a distinct WARC output directory and a more conservative `fetcher.server.delay`. Run it like the production topology, but with the bootstrap main class and config:

``` sh
storm local target/crawler-3.6.0.jar --local-ttl 60 -- org.commoncrawl.stormcrawler.news.bootstrap.BootstrapTopology -conf $PWD/conf/opensearch-conf.yaml -conf $PWD/conf/bootstrap-conf.yaml $PWD/seeds/ feeds.txt
```


## Monitor the crawl

When the topology is running you can check that URLs have been injected and news are getting fetched on <http://localhost:9200/status/_search?pretty>. Or use StormCrawler's OpenSearch dashboards to monitor the crawling process on <http://localhost:5601/>.

There is also a shell script [bin/status](./bin/status) to get aggregated counts from the status index, and to add, delete or force a re-fetch of URLs. E.g.,

```
$> bin/status aggregate_status
3824    DISCOVERED
34      FETCHED
5       REDIRECTION
```

Run `bin/status` without arguments to see the full list of sub-commands (each sub-command is a function inside the script). Pass `-C` to colorize the JSON output.


## Run Crawl with Docker Compose

Do not forget to create the uberjar (see above) which is included in the Docker image. Simply run:

```
mvn clean package
```

Verify the configuration in the file [docker-compose.yaml](docker-compose.yaml) and [conf/](conf/) is correct:
- Don't forget to adapt the paths to mounted volumes used to persist data (OpenSearch indexes and WARC files).
- Make sure to add the user agent configuration in conf/crawler-conf.yaml.
- If the FastURLFilter rules file is loaded from S3 (`fast.urlfilter.file: "s3://..."` in `conf/`), the worker needs AWS credentials and a region. The `storm-supervisor` service selects a named profile via `AWS_PROFILE`/`AWS_REGION` (defaults in [docker-compose.yaml](docker-compose.yaml), overridable through a `.env` file) and mounts the host's `~/.aws` read-only at `/home/storm/.aws`. It is mounted there — the `storm` user's home — rather than `/root`, because the worker JVM runs as the `storm` user, so that is where the AWS SDK's default `~/.aws` lookup resolves. Make sure the profile exists in your `~/.aws/credentials` (and `~/.aws/config` for region / SSO / role).

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
- Make sure that the OpenSearch port 9200 is not already in use or mapped by a running OpenSearch instance. Otherwise, OpenSearch commands may affect the running instance!


Submit the topology from the `news-crawler` service. The main path uses [Storm Flux](https://storm.apache.org/releases/2.8.8/flux.html) with `conf/crawler.flux`:
```
docker compose run --rm news-crawler \
    storm jar lib/crawler.jar org.apache.storm.flux.Flux --remote /news-crawler/conf/crawler.flux
```
Or run the equivalent Java topology (the `--` separator overrides the manifest's default Flux main class):
```
docker compose run --rm news-crawler \
    storm jar lib/crawler.jar -- org.commoncrawl.stormcrawler.news.CrawlTopology \
    /data/seeds '*' -conf conf/opensearch-conf.yaml -conf conf/crawler-conf.yaml
```

After 1-2 minutes if everything is up, connect to OpenSearch on port [9200](http://localhost:9200/) or the OpenSearch dashboards on port [5601](http://localhost:5601/).

For inspecting the worker log files (the container name matches `container_name:` for the `storm-supervisor` service in [docker-compose.yaml](docker-compose.yaml)):
```
docker exec storm-supervisor-news-crawl /bin/bash -c 'cat /logs/workers-artifacts/*/*/worker.log'
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

## Note for developers

Requires **JDK 17+** (see [.java-version](.java-version)). Common commands:

```
mvn clean package                         # build the shaded uberjar → target/crawler-3.6.0.jar
mvn test                                  # run all JUnit tests
mvn test -Dtest=NewsSiteMapParserTest     # run a single test class
```

Code style is [google-java-format](https://github.com/google/google-java-format) (AOSP profile), enforced in CI by the Cosium `git-code-format-maven-plugin`. The plugin is gated behind the `skip.format.code` property (default `true`), so you must pass `-Dskip.format.code=false` to run it locally.

Please format your code before submitting a PR with

```
mvn git-code-format:format-code -Dgcf.globPattern="**/*" -Dskip.format.code=false
```

To only check formatting (as CI does), without modifying files:

```
mvn git-code-format:validate-code-format -Dgcf.globPattern="**/*" -Dskip.format.code=false
```

You can enable pre-commit format hooks by running:

```
mvn clean install -Dskip.format.code=false
```


