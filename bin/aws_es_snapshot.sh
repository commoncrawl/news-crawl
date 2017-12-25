#!/bin/bash

set -e
set -x

curl -H Content-Type:application/json -XPUT http://localhost:9200/_snapshot/news_crawler_status  --data '{
  "type": "s3",
  "settings": {
    "region": "us-east-1",
    "bucket": "commoncrawl-news-crawler",
    "base_path": "es-snapshots-2"
  }
}'


TIMESTAMP=`date +%Y-%m-%d-%H-%M`

curl -H Content-Type:application/json -XPUT 'http://localhost:9200/_snapshot/news_crawler_status/'$TIMESTAMP'?wait_for_completion=true' --data '{
    "indices": "status",
    "ignore_unavailable": "true",
    "include_global_state": false
}'

