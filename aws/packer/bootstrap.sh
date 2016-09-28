#!/bin/bash


set -e

sudo sh -c 'echo "LANG=en_US.utf-8" >> /etc/environment'
sudo sh -c 'echo "LC_ALL=en_US.utf-8" >> /etc/environment'

sh -c 'echo "syntax on" >~/.vimrc'

sudo yum update -y
sudo yum remove  -y java-1.7.0-openjdk
sudo yum install -y java-1.8.0-openjdk-devel git

#
# Supervisord
#
sudo pip install supervisor
sudo mkdir /etc/supervisor/
sudo mkdir /etc/supervisor/conf.d/
sudo mkdir /var/log/supervisor/
sudo cp /tmp/install/etc/supervisord.conf /etc/supervisor/
# TODO: make sure that there is only one init script/daemon for any service
#       not init.d + supervisor

#
# Elasticsearch and Kibana
#
# see https://www.elastic.co/guide/en/elasticsearch/reference/master/rpm.html
#
sudo rpm --import https://packages.elastic.co/GPG-KEY-elasticsearch
sudo bash -c 'cat >/etc/yum.repos.d/elasticsearch.repo <<"EOF"
[elasticsearch-2.x]
name=Elasticsearch repository for 2.x packages
baseurl=https://packages.elastic.co/elasticsearch/2.x/centos
gpgcheck=1
gpgkey=https://packages.elastic.co/GPG-KEY-elasticsearch
enabled=1
autorefresh=1
type=rpm-md

[kibana-4.5]
name=Kibana repository for 4.5.x packages
baseurl=http://packages.elastic.co/kibana/4.5/centos
gpgcheck=1
gpgkey=http://packages.elastic.co/GPG-KEY-elasticsearch
enabled=1
autorefresh=1
type=rpm-md
EOF'

sudo yum install -y elasticsearch-2.3.1 kibana-4.5.1
sudo chkconfig --add elasticsearch

sudo -u elasticsearch /usr/share/elasticsearch/bin/plugin install -b license
sudo -u elasticsearch /usr/share/elasticsearch/bin/plugin install -b marvel-agent

sudo /opt/kibana/bin/kibana plugin --install elasticsearch/marvel/latest
sudo /opt/kibana/bin/kibana plugin --install elastic/sense

sudo ln -s /usr/share/elasticsearch/bin/elasticsearch /usr/bin/elasticsearch
sudo ln -s /opt/kibana/bin/kibana /usr/bin/kibana

sudo groupadd kibana && sudo useradd --gid kibana kibana
sudo chown -R kibana:kibana /var/log/kibana
sudo chown -R kibana:kibana /opt/kibana/

sudo bash <<EOF
cat /tmp/install/etc/sysctl.conf >>/etc/sysctl.conf
cp /tmp/install/elasticsearch/*.conf /etc/supervisor/conf.d/
echo environment=ES_HEAP_SIZE="10g" >>/etc/supervisor/conf.d/elasticsearch.conf
EOF

# must start elasticsearch via supervisorctl
# TODO: avoid issues if it's started erroneously via
#          /etc/init.d/elasticsearch
#       - remove the init script or
#       - sync the config in /etc/ with that in /etc/supervisor/conf.d/


#
# Apache Storm and Zookeeper
#
ZOOKEEPER_VERSION=3.4.8
wget -q -O - http://mirrors.ukfast.co.uk/sites/ftp.apache.org/zookeeper/zookeeper-$ZOOKEEPER_VERSION/zookeeper-$ZOOKEEPER_VERSION.tar.gz \
    | sudo tar -xzf - -C /opt
ZOOKEEPER_HOME=/opt/zookeeper-$ZOOKEEPER_VERSION
STORM_VERSION=1.0.1
wget -q -O - http://mirrors.ukfast.co.uk/sites/ftp.apache.org/storm/apache-storm-$STORM_VERSION/apache-storm-$STORM_VERSION.tar.gz \
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
cp /tmp/install/storm/*.conf /etc/supervisor/conf.d/
chmod 644 /etc/supervisor/conf.d/*.conf
EOF



#
# Storm crawler / News crawler
#
cp /tmp/install/newscrawler .
mkdir -p news-crawler/{conf,bin,lib,seeds}
# seeds must readable for user "storm"
chmod a+rx news-crawler/seeds/
chmod 644 news-crawler/seeds/*
cp /tmp/install/run-crawler.sh news-crawler/bin/
cp /tmp/install/news-crawler/lib/crawler-1.0-SNAPSHOT.jar news-crawler/lib/
wget -O news-crawler/bin/ES_IndexInit.sh https://raw.githubusercontent.com/DigitalPebble/storm-crawler/master/external/elasticsearch/ES_IndexInit.sh
chmod u+x news-crawler/bin/*


#
# Volumes
#
sudo bash <<EOF
mkdir /data/elasticsearch /data/warc
echo "/dev/xvdb   /data/elasticsearch  auto    defaults,nofail,comment=cloudconfig     0       2"  >>/etc/fstab
echo "/dev/xvdc   /data/warc           auto    defaults,nofail,comment=cloudconfig     0       2"  >>/etc/fstab
EOF

# TODO: mount volumes and set owner and permissions
# mount /data/elasticsearch
# chown -R elasticsearch:elasticsearch /data/elasticsearch
# mount /data/warc
# chown -R storm:storm /data/warc


# TODO: cronjob to upload WARC files

