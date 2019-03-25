FROM ubuntu:18.04

RUN apt-get update -qq && \
	apt-get upgrade -yq && \
	apt-mark hold openjdk-11-jre-headless && \
	apt-get install -yq --no-install-recommends \
		apt-transport-https \
		apt-utils \
		ca-certificates \
		curl \
		git-core \
		gnupg \
		jq \
		less \
		maven \
		openjdk-8-jdk-headless \
		sudo \
		supervisor \
		wget \
		tar \
		vim \
		zookeeperd

#
# Elasticsearch and Kibana
#
ENV ES_VERSION=7.0.0
RUN wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch \
	| apt-key add -
RUN echo "deb https://artifacts.elastic.co/packages/7.x/apt stable main" \
	>> /etc/apt/sources.list.d/elasticsearch-7.x.list
RUN apt-get update -qq && \
	apt-get install -yq --no-install-recommends \
		elasticsearch=$ES_VERSION \
		kibana=$ES_VERSION
RUN ln -s /usr/share/elasticsearch/bin/elasticsearch /usr/bin/elasticsearch
RUN ln -s /usr/share/kibana/bin/kibana /usr/bin/kibana
RUN chown -R kibana:kibana /usr/share/kibana/
USER root
# system configuration, see https://www.elastic.co/guide/en/elasticsearch/reference/current/deb.html
ADD etc/sysctl.d/60-elasticsearch.conf       /etc/sysctl.d/60-elasticsearch.conf
ADD etc/supervisor/conf.d/elasticsearch.conf /etc/supervisor/conf.d/elasticsearch.conf
ADD etc/supervisor/conf.d/kibana.conf        /etc/supervisor/conf.d/kibana.conf
RUN chmod -R 644 /etc/sysctl.d/60-elasticsearch.conf /etc/supervisor/conf.d/*.conf
ENV ES_HEAP_SIZE=20g
# set Elasticsearch data path
RUN sed -Ei 's@^path\.data: .*@path.data: /data/elasticsearch@' /etc/elasticsearch/elasticsearch.yml
# TODO: enable updates via scripting

#
# Apache Storm
#
ENV STORM_VERSION=1.2.2
COPY downloads/apache-storm-$STORM_VERSION.tar.gz /tmp/apache-storm-$STORM_VERSION.tar.gz
RUN tar -xzf /tmp/apache-storm-$STORM_VERSION.tar.gz -C /opt
RUN rm /tmp/apache-storm-$STORM_VERSION.tar.gz
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
ADD target/crawler-1.14.jar news-crawler/lib/crawler.jar
# and configuration files
ADD conf/*.*        news-crawler/conf/
ADD seeds/*.txt     news-crawler/seeds/
ADD bin/*.sh        news-crawler/bin/
ADD bin/es_status   news-crawler/bin/

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

