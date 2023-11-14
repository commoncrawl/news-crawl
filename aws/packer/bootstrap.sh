#!/bin/bash


set -e

sudo sh -c 'echo "LANG=en_US.utf-8" >> /etc/environment'
sudo sh -c 'echo "LC_ALL=en_US.utf-8" >> /etc/environment'

sh -c 'echo "syntax on" >~/.vimrc'

sudo yum update -y
sudo yum install -y java-1.8.0-openjdk-devel git jq python2-pip

#
# Supervisord
#
sudo pip install supervisor
sudo mkdir /etc/supervisor/
sudo mkdir /etc/supervisor/conf.d/
sudo mkdir /var/log/supervisor/
sudo cp /tmp/install/etc/supervisor/supervisord.conf /etc/supervisor/supervisord.conf
# TODO: make sure that there is only one init script/daemon for any service
#       not init.d + supervisor, see note below for Elasticsearch

#
# Elasticsearch and Kibana
#
# see https://www.elastic.co/guide/en/elasticsearch/reference/master/rpm.html
#
ES_VERSION=7.3.0
sudo rpm --import https://artifacts.elastic.co/GPG-KEY-elasticsearch
sudo bash -c 'cat >/etc/yum.repos.d/elasticsearch.repo <<"EOF"
[elasticsearch-7.x]
name=Elasticsearch repository for 7.x packages
baseurl=https://artifacts.elastic.co/packages/7.x/yum
gpgcheck=1
gpgkey=https://artifacts.elastic.co/GPG-KEY-elasticsearch
enabled=1
autorefresh=1
type=rpm-md

[kibana-7.x]
name=Kibana repository for 7.x packages
baseurl=https://artifacts.elastic.co/packages/7.x/yum
gpgcheck=1
gpgkey=https://artifacts.elastic.co/GPG-KEY-elasticsearch
enabled=1
autorefresh=1
type=rpm-md
EOF'

sudo yum install -y elasticsearch-$ES_VERSION kibana-$ES_VERSION
sudo chkconfig --add elasticsearch

sudo /usr/share/elasticsearch/bin/elasticsearch-plugin install -b repository-s3

sudo ln -s /usr/share/elasticsearch/bin/elasticsearch /usr/bin/elasticsearch
sudo ln -s /usr/share/kibana/bin/kibana               /usr/bin/kibana

sudo cp /tmp/install/etc/sysctl.d/60-elasticsearch.conf        /etc/sysctl.d/
sudo cp /tmp/install/etc/supervisor/conf.d/elasticsearch.conf  /etc/supervisor/conf.d/
sudo cp /tmp/install/etc/supervisor/conf.d/kibana.conf         /etc/supervisor/conf.d/

# set Elasticsearch data path
sudo sed -Ei 's@^path\.data: .*@path.data: /data/elasticsearch@' /etc/elasticsearch/elasticsearch.yml
# TODO: enable updates via scripting

# must start elasticsearch via supervisorctl
# TODO: avoid issues if it's started erroneously via
#          /etc/init.d/elasticsearch
#       - remove the init script or
#       - sync the config in /etc/ with that in /etc/supervisor/conf.d/


#
# Apache Storm and Zookeeper
#
ZOOKEEPER_VERSION=3.4.14
wget -q -O - http://www-us.apache.org/dist/zookeeper/zookeeper-$ZOOKEEPER_VERSION/zookeeper-$ZOOKEEPER_VERSION.tar.gz \
    | sudo tar -xzf - -C /opt
ZOOKEEPER_HOME=/opt/zookeeper-$ZOOKEEPER_VERSION
STORM_VERSION=1.2.4
wget -q -O - https://downloads.apache.org/storm/apache-storm-$STORM_VERSION/apache-storm-$STORM_VERSION.tar.gz \
    | sudo tar -xzf - -C /opt
STORM_HOME=/opt/apache-storm-$STORM_VERSION
sudo groupadd storm
sudo useradd --gid storm --home-dir /home/storm \
             --create-home --shell /bin/bash storm
sudo chown -R storm:storm $STORM_HOME
sudo mkdir /var/log/storm
sudo chown -R storm:storm /var/log/storm
sudo ln -s /var/log/storm $STORM_HOME/logs
sudo ln -s $STORM_HOME/bin/storm /usr/bin/storm
sudo ln -s $ZOOKEEPER_HOME/conf/zoo_sample.cfg $ZOOKEEPER_HOME/conf/zoo.cfg
sudo ln -s $ZOOKEEPER_HOME /usr/share/zookeeper
sudo bash <<EOF
cp /tmp/install/etc/supervisor/conf.d/storm-*.conf   /etc/supervisor/conf.d/
cp /tmp/install/etc/supervisor/conf.d/zookeeper.conf /etc/supervisor/conf.d/
chmod 644 /etc/supervisor/conf.d/*.conf
EOF



#
# Storm crawler / News crawler
#
cp -r /tmp/install/news-crawler .
mkdir -p news-crawler/{conf,bin,lib,seeds}
# seeds must be readable for user "storm"
chmod a+rx news-crawler/seeds/
chmod 644 news-crawler/seeds/*
cp /tmp/install/news-crawler/lib/crawler-1.18.jar news-crawler/lib/
chmod u+x news-crawler/bin/*


#
# Volumes
#
sudo bash <<EOF
mkdir /data
echo "/dev/sdb    /data     auto    defaults,nofail,comment=cloudconfig     0       2"  >>/etc/fstab
EOF

# mount volumes and set owner and permissions
sudo mount /data
sudo mkdir /data/elasticsearch
sudo chown -R elasticsearch:elasticsearch /data/elasticsearch
sudo mkdir /data/warc
sudo chown -R storm:storm /data/warc


# TODO cronjobs:
# - to upload WARC files
# - to backup Elasticsearch status index
