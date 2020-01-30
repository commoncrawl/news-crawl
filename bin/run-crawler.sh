#!/bin/bash

# in case volumes are on the host need to adjust permissions
chown -R elasticsearch:elasticsearch /data/elasticsearch
chown -R storm:storm /data/warc

export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

# as root
/usr/bin/supervisord

# wait until Storm ad Elasticsearch are running
sleep 60

# start the news crawler as user ubuntu
sudo -iu ubuntu /bin/bash <<"EOF"

set -e

cd $HOME/news-crawler/

# initialize Elasticsearch indices
# CAVEAT: this deletes existing indices!
bin/ES_IndexInit.sh
sleep 10

STORMCRAWLER="storm jar $PWD/lib/crawler.jar"

# run the crawler
$STORMCRAWLER org.commoncrawl.stormcrawler.news.CrawlTopology \
	$PWD/seeds '*' -conf $PWD/conf/es-conf.yaml -conf $PWD/conf/crawler-conf.yaml
# alternatively running the flux
#$STORMCRAWLER org.apache.storm.flux.Flux --remote $PWD/conf/crawler.flux
# suppress warnings about malformed XML in sitemaps
storm set_log_level NewsCrawl \
      -l crawlercommons.sitemaps.SiteMapParser=ERROR


EOF
