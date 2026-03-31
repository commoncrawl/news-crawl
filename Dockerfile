FROM storm:2.8.4

RUN apt-get update -qq && \
	apt-get install -yq --no-install-recommends \
		curl \
		jq \
		less \
		vim

#
# news-crawler
#
ENV CRAWLER_VERSION=3.5.1

RUN mkdir /news-crawler/ && \
	mkdir /news-crawler/conf/ && \
	mkdir /news-crawler/lib/ && \
	mkdir /news-crawler/bin/ && \
	chmod -R a+rx /news-crawler/
# add the news crawler uber-jar
ADD target/crawler-$CRAWLER_VERSION.jar /news-crawler/lib/crawler.jar
# and configuration files
ADD conf/*.*        /news-crawler/conf/
ADD bin/*.sh        /news-crawler/bin/
ADD bin/status      /news-crawler/bin/

USER storm
WORKDIR /news-crawler/

