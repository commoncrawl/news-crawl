#!/bin/bash

# in case volumes are on the host need to adjust permissions
chown -R elasticsearch:elasticsearch /data/elasticsearch
chown -R storm:storm /data/warc

# as root
/usr/bin/supervisord

# wait until Storm ad Elasticsearch are running
sleep 60

# start the news crawler as user ubuntu
sudo -iu ubuntu /bin/bash <<"EOF"

cd $HOME/news-crawler/

# initialize Elasticsearch indices
# CAVEAT: this deletes existing indices!
bin/ES_IndexInit.sh
sleep 10

STORMCRAWLER="storm jar $PWD/lib/crawler.jar"

# inject seeds into Elasticsearch
$STORMCRAWLER com.digitalpebble.stormcrawler.elasticsearch.ESSeedInjector \
	$PWD/seeds '*' -conf $PWD/conf/es-conf.yaml -conf $PWD/conf/crawler-conf.yaml
sleep 20

# run the crawler
$STORMCRAWLER com.digitalpebble.stormcrawler.CrawlTopology \
	-conf $PWD/conf/es-conf.yaml -conf $PWD/conf/crawler-conf.yaml
storm set_log_level NewsCrawl \
      -l crawlercommons.sitemaps.SiteMapParser=ERROR


EOF
