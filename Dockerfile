FROM ubuntu:16.04

RUN apt-get update -qq && \
	apt-get upgrade -yq && \
	apt-get install -yq --no-install-recommends \
		apt-transport-https \
		apt-utils \
		ca-certificates \
		curl \
		git-core \
		less \
		maven \
		openjdk-8-jdk-headless \
		sudo \
		supervisor \
		wget \
		tar \
		zookeeperd

#
# Elasticsearch and Kibana
#
RUN wget -qO - https://packages.elastic.co/GPG-KEY-elasticsearch \
	| apt-key add -
RUN echo "deb https://packages.elastic.co/elasticsearch/2.x/debian stable main" \
	>> /etc/apt/sources.list.d/elasticsearch-2.x.list
RUN echo "deb http://packages.elastic.co/kibana/4.5/debian stable main" \
	>> /etc/apt/sources.list.d/elasticsearch-2.x.list
RUN apt-get update -qq && \
	apt-get install -yq --no-install-recommends \
		elasticsearch=2.3.1 \
        kibana=4.5.1
RUN ln -s /usr/share/elasticsearch/bin/elasticsearch /usr/bin/elasticsearch
RUN ln -s /opt/kibana/bin/kibana /usr/bin/kibana
RUN mkdir /var/log/kibana && chown -R kibana:kibana /var/log/kibana
RUN chown -R kibana:kibana /opt/kibana/
# install marvel, see https://www.elastic.co/downloads/marvel
USER elasticsearch
RUN /usr/share/elasticsearch/bin/plugin install license
RUN /usr/share/elasticsearch/bin/plugin install marvel-agent
USER kibana
RUN /opt/kibana/bin/kibana plugin --install elasticsearch/marvel/latest
RUN /opt/kibana/bin/kibana plugin --install elastic/sense
USER root
# system configuration, see https://www.elastic.co/guide/en/elasticsearch/reference/current/setup-configuration.html
ADD etc/sysctl.d/60-elasticsearch.conf       /etc/sysctl.d/60-elasticsearch.conf
ADD etc/supervisor/conf.d/elasticsearch.conf /etc/supervisor/conf.d/elasticsearch.conf
ADD etc/supervisor/conf.d/kibana.conf        /etc/supervisor/conf.d/kibana.conf
RUN chmod -R 644 /etc/sysctl.d/60-elasticsearch.conf /etc/supervisor/conf.d/*.conf
ENV ES_HEAP_SIZE=20g


#
# Apache Storm
#
ENV STORM_VERSION=1.0.1
RUN wget -q -O - http://mirrors.ukfast.co.uk/sites/ftp.apache.org/storm/apache-storm-$STORM_VERSION/apache-storm-$STORM_VERSION.tar.gz \
	| tar -xzf - -C /opt
ENV STORM_HOME /opt/apache-storm-$STORM_VERSION
RUN groupadd storm && \
	useradd --gid storm --home-dir /home/storm \
			--create-home --shell /bin/bash storm && \
	chown -R storm:storm $STORM_HOME && \
	mkdir /var/log/storm && \
	chown -R storm:storm /var/log/storm
RUN ln -s /var/log/storm $STORM_HOME/logs
RUN ln -s $STORM_HOME/bin/storm /usr/bin/storm
ADD etc/supervisor/conf.d/storm-*.conf   /etc/supervisor/conf.d/
ADD etc/supervisor/conf.d/zookeeper.conf /etc/supervisor/conf.d/
RUN chmod -R 644 /etc/supervisor/conf.d/*.conf


#
# Storm crawler
#
RUN groupadd ubuntu && \
	useradd --gid ubuntu --home-dir /home/ubuntu \
			--create-home --shell /bin/bash ubuntu && \
	chown -R ubuntu:ubuntu /home/ubuntu
USER ubuntu
WORKDIR /home/ubuntu
RUN mkdir news-crawler/ && \
	mkdir news-crawler/conf/ && \
    mkdir news-crawler/lib/ && \
    mkdir news-crawler/bin/ && \
    mkdir news-crawler/seeds/ && \
    chmod -R a+rx news-crawler/
# add the news crawler uber-jar
ADD target/crawler-1.0-SNAPSHOT.jar news-crawler/lib/
# and configuration files
ADD conf/*.*        news-crawler/conf/
ADD seeds/feeds.txt news-crawler/seeds/
ADD bin/*.sh        news-crawler/bin/
# add storm-crawler/external/elasticsearch/ES_IndexInit.sh
RUN wget -O     news-crawler/bin/ES_IndexInit.sh \
	https://raw.githubusercontent.com/DigitalPebble/storm-crawler/master/external/elasticsearch/ES_IndexInit.sh
USER root
RUN chown -R ubuntu:ubuntu /home/ubuntu && \
	chmod -R a+r /home/ubuntu && \
	chmod u+x news-crawler/bin/*


# Ports:
#  8080 - Storm UI
#  9200 - Elasticsearch http
#  9300 - Elasticsearch java
#  5601 - Kibana
EXPOSE 8080 9200 9300 5601

# volumes for persistent data
USER root
RUN mkdir /data
RUN mkdir /data/elasticsearch && chown elasticsearch:elasticsearch /data/elasticsearch
VOLUME ["/data/elasticsearch"]
RUN mkdir /data/warc && chown storm:storm /data/warc
VOLUME ["/data/warc"]

# start all services
CMD ["/usr/bin/supervisord"]

# launch the crawl
# CMD ["/home/ubuntu/news-crawler/bin/run-crawler.sh"]

