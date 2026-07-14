#!/bin/bash

# Run StormCrawler or News Crawler main methods
# for testing and debugging.

function show_help() {
    cat >&2 <<EOF

Run StormCrawler main methods.

$0 [-D] CLASS [OPTIONS] ...

Short-cuts for some known classes with main methods:
  $0 [-D] protocol okhttp|httpclient|file|selenium|playwright [OPTIONS] SEEDS
  $0 [-D] parser [OPTIONS] SEEDS
  $0 [-D] urlfilter [OPTIONS] URL

General script options:
  -D enable debugging on port 37649

Known class options (no available for every class):
  -c conf.yaml  custom crawler configuration file (parser)
  -f conf.yaml  custom crawler configuration file (protocol)
  -f conf.json  JSON URL filter configuration file
                (loaded from Java class path)

Examples:
  $0 protocol okhttp -f conf/crawler-conf.yaml https://example.com/
  $0 parser          -c conf/crawler-conf.yaml https://example.com/
  $0 urlfilter       -f urlfilters-test.json   https://example.com/

EOF
}

if [ -z "$JAVA_HOME" ]; then
    echo "JAVA_HOME needs to be defined. Java 17 is required."
    exit 1;
fi

if [ -z "$STORM_HOME" ]; then
    echo "STORM_HOME needs to be defined."
    echo "It should point to the proper Storm version used by StormCrawler."
    echo "Please, download it from https://storm.apache.org/downloads.html,"
    echo "unpack the binary package or compile the source package,"
    echo "and point STORM_HOME to the installation directory, e.g.,"
    echo "  STORM_HOME=\"/path/to/downloads/apache-storm-2.8.8\""
    echo "  export STORM_HOME"
    exit 1;
fi

DIR="$PWD"

CRAWLER_JAR=$(ls "$DIR"/target/*crawler*-[1-9].[0-9]{[0-9],}{.[0-9],}{,-SNAPSHOT}.jar 2>/dev/null | grep -v target/original-)
if [ -z "$CRAWLER_JAR" ]; then
    echo "No crawler jar found."
    echo "Please, run `mvn clean package` to compile the crawler jar."
    exit 1
fi

CLASSPATH="$CRAWLER_JAR":$(ls $STORM_HOME/lib/*.jar | tr '\n' :)

if [ "$1" == "-D" ]; then
    DEBUG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:37649"
    shift
fi

CMD="$1"; shift

case "$CMD" in
    "protocol" )
        PCKG="$1"; shift
        case "$PCKG" in
            "selenium" )
                CLASS="org.apache.stormcrawler.protocol.$PCKG.RemoteDriverProtocol"
                ;;
            "file" )
                CLASS="org.apache.stormcrawler.protocol.$PCKG.FileProtocol"
                ;;
            * )
                # httpclient, okhttp
                CLASS="org.apache.stormcrawler.protocol.$PCKG.HttpProtocol"
                ;;
        esac
        ;;
    "parser" )
        CLASS="org.apache.stormcrawler.parse.ParseFilters"
        ;;
    "urlfilter" )
        CLASS="org.apache.stormcrawler.filtering.URLFilters"
        ;;
    "" | "--help" | "-h" | "-?" )
        show_help
        exit 1
        ;;
    * )
        CLASS="$CMD"
        ;;
esac

set -x

$JAVA_HOME/bin/java $DEBUG -cp $CLASSPATH $CLASS "$@"

