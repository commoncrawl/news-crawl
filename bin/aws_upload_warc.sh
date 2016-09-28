#!/bin/bash

# upload all WARC files except for the newest to S3
# and remove it from the local directory after a successful upload

# NOTE: to be run as user "storm"

set -e

S3PATH=s3://commoncrawl/crawl-data/CC-NEWS

echo "Uploading WARC files to S3"
date

ls /data/warc/CC-NEWS-*.warc.gz | sort | head -n -1 \
    | while read warcpath; do
    warcfile=$(basename $warcpath)
    warcdate=${warcfile##CC-NEWS-}
    warcdate=${warcdate%%-*.warc.gz}
    year=${warcdate:0:4}
    month=${warcdate:4:2}
    echo "upload $warcpath -> $S3PATH/$year/$month/$warcfile"
    aws s3 cp $warcpath $S3PATH/$year/$month/$warcfile --acl public-read
    echo "remove $warcpath"
    rm $warcpath
    crc=$(dirname $warcpath)/.$(basename $warcpath.crc)
    if [ -e $crc ]; then
        echo "remove checksum file $crc"
        rm $crc
    fi
done

echo
