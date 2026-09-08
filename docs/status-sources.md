# Feeds and sitemaps in the status index

`bin/status` has three sub-commands to inspect and re-arm the *crawl sources* of a host or domain, i.e. 
its feeds and sitemaps: `domain_sources`, `refetch_sources` and `filtered_sources`. 

They complement `domain_report`, which gives the status counts of a
domain. The typical use is after a host or domain has been removed from the fast URL
filter: find the feeds and sitemaps the filter killed and schedule them again.

All commands need `curl` and `jq`, and read the index URL from `ES_STATUS_URL`
(default `http://localhost:9200/status`).

## How sources are represented

A document in the status index is a source if one of these metadata flags is `"true"`:

| flag | set by | meaning |
|---|---|---|
| `isfeed` | `FeedParserBolt`, `FeedDetectorBolt` | RSS/Atom feed |
| `issitemapnews` | `NewsSiteMapParserBolt` | Google News sitemap |
| `issitemapindex` | `NewsSiteMapParserBolt` | sitemap index (links to other sitemaps) |
| `issitemapverified` | `NewsSiteMapParserBolt` | sitemap verified to contain news |
| `issitemap` | sitemap discovery (robots.txt), `SiteMapParserBolt` | plain sitemap, not (yet) known to carry news |

Flags are only assigned when a document is fetched and parsed (except `issitemap`, which
sitemap discovery sets from robots.txt). 
A seed that was rejected by the pre-filter
before its first fetch therefore has **no flag at all**: it is an ERROR document with
`error.cause=Filtered` and nothing else. 
The commands below call such documents
*unflagged* and find them by a name heuristic: the URL contains `sitemap`, `rss`,
`feed` or `atom`, not followed by a letter (so `feeding` or `atomic` do not match).

Because it is only a guess based on the URL, an `unflagged` row is a *candidate*, not
a confirmed feed or sitemap. An ordinary article whose URL contains one of those words,
such as `/world/china-feed-industry-unlikely-to-become-self-sufficient`, is listed as
well although it is not a feed. Before re-arming `unflagged` rows, read their URLs and
keep only those that look like a feed or sitemap address (`/rss.xml`, `/feed`,
`/sitemap.xml`, `/feeds/posts/default`, ...).

## `domain_sources <HOST/DOMAIN>...`

Lists every source of the domain as a TSV table, one row per document, sorted by type
and URL, followed by a summary line with counts per type.

```
$ bin/status domain_sources example.com | column -t -s $'\t'
===== example.com
TYPE          STATUS   NEXT_FETCH                LAST_FETCH            INTERVAL  LINKS  HTTP  LAST_MODIFIED                  ERROR/REDIRECT  URL
feed          FETCHED  2026-09-08T18:00:00.000Z  2026-09-08T06:00:00Z  720       48     200   Tue, 08 Sep 2026 06:00:00 GMT  -               https://www.example.com/rss/homepage.xml
sitemap-news  FETCHED  2026-09-09T06:00:00.000Z  2026-09-08T06:01:40Z  1440      312    200   -                              -               https://www.example.com/sitemap-news.xml
sitemap       ERROR    -                         -                     -         -      -     -                              Filtered        https://www.example.com/sitemap-old.xml
-- 3 sources: 1 feed, 1 sitemap, 1 sitemap-news
```

| column | source |
|---|---|
| TYPE | `feed`, `sitemap-news`, `sitemap-index`, `sitemap-verified`, `sitemap`, `unflagged` (first flag wins, in this order) |
| STATUS | `status` |
| NEXT_FETCH | `nextFetchDate`; `-` if the document is not scheduled |
| LAST_FETCH | `protocol._request.time_` converted from epoch milliseconds |
| INTERVAL | `fetchinterval` in minutes, as set by the scheduler |
| LINKS | `numlinks`: outlinks found at the last parse |
| HTTP | `fetch.statuscode` |
| LAST_MODIFIED | `last-modified` header of the last fetch |
| ERROR/REDIRECT | all values of `error.cause`, or the redirect target for REDIRECTION rows |

The query is routed to the domain's shard (`key` is the routing field), so it is cheap
even on a large index. At most 10000 rows are returned (`index.max_result_window`);
feeds and news/index/verified sitemaps are fetched first so that, if the cap is hit,
only plain sitemaps and unflagged candidates are cut off, and a warning with the exact
total is printed.

Handy filters on the output:

```sh
bin/status domain_sources example.com | sed '/^--/q' | column -t -s $'\t'   # table only
bin/status domain_sources example.com | grep -v $'^sitemap\t'               # hide plain sitemaps
bin/status domain_sources example.com | cut -f10 | awk -F/ '{print $3}' | sort | uniq -c | sort -rn   # hosts
```

## `refetch_sources [search|count|update] <HOST/DOMAIN> [<URL-REGEXP> [<TYPES>]]`

Selects the sources of a domain and, with `update`, re-arms them so that they are
fetched again:

- `status` is set to `DISCOVERED`;
- `nextFetchDate` is set to `$REFETCH_DATE`, default `now` (see below);
- `last-modified`, `protocol.etag` and `signaturechangedate` are removed, so the next
  fetch is a full one rather than a `304 Not Modified`;
- `error.cause` and `error.source` are removed;
- the flags are kept, so after the fetch the usual feed/sitemap intervals apply.

`search` prints the same table as `domain_sources` for the selected documents, `count`
prints their number. Always run one of them before `update`.

Arguments:

- **URL-REGEXP** (default `.*`) is an OpenSearch regular expression that must match the
  **whole URL**, so start it with `.*` or the scheme. Escape literal dots (`\.`) and
  question marks (`\?`), and quote the argument, otherwise the shell expands `.*` to the
  dot files of the current directory.
- **TYPES** (default: all flagged types, i.e. without `unflagged`) is a comma-separated
  list of `feed`, `sitemap`, `sitemap-news`, `sitemap-index`, `sitemap-verified`,
  `unflagged`. When `unflagged` is requested together with other types, documents
  carrying one of the flags *not* requested are excluded, so `feed,unflagged` cannot
  re-arm a sitemap named `sitemap.xml`.

```sh
bin/status refetch_sources search example.com
bin/status refetch_sources update example.com '.*/sitemap\.xml'
bin/status refetch_sources update example.com '.*' feed,sitemap-news
```

### `REFETCH_DATE`: mild or front of the queue

The spout takes at most `opensearch.status.max.urls.per.bucket` URLs (5) per domain and
query, **in ascending `nextFetchDate` order**, and the fetcher waits
`fetcher.server.delay` (9 s) between requests to the same domain. Two consequences:

- With the default `REFETCH_DATE=now` the re-armed sources queue up *behind* every
  older DISCOVERED URL of the domain. For a domain with a short queue that is minutes;
  for a domain with a backlog of a million discovered URLs it is weeks.
- A date in the past puts them *first*, and the bucket itself first among the buckets:

  ```sh
  REFETCH_DATE=2000-01-01 bin/status refetch_sources update example.com '.*' feed,sitemap-news
  ```

`domain_report <domain>` shows the size of the queue: a large DISCOVERED count with
SCHEDULED equal to it means a long wait with the default.

For the same reason `fetch_now_url` is the wrong tool for a domain with a backlog, and
`fetch_at_url <URL> 2000-01-01` the right one for a single URL.

## `filtered_sources [search|count|update] <HOST/DOMAIN> [<URL-REGEXP> [<TYPES>]]`

Same as `refetch_sources`, restricted to documents rejected by the pre-filter
(`error.cause` contains `Filtered`), and including `unflagged` candidates by default.
This is the command to use after a host or domain has been removed from the fast URL
filter.

```sh
bin/status filtered_sources search example.com          # what did the filter kill?
bin/status filtered_sources update example.com          # re-arm all of it
bin/status filtered_sources update feedburner.com '.*' feed   # only the flagged feeds
```

## Workflow: after removing hosts or domains from the fast URL filter

1. **Make sure the crawler has reloaded the filter.** Rules are loaded from S3 and
   reloaded every `fast.urlfilter.refresh` seconds; if the property is unset or `-1`,
   only a topology restart picks up the change. Re-armed URLs of a still-filtered domain
   go straight back to ERROR/Filtered.

2. **Triage.** For a list of domains:

   ```sh
   for d in example.com example.org; do
       bin/status filtered_sources search "$d" > "$d.filtered"
       printf '%-28s %s\n' "$d" "$(grep -E '^(--|WARNING)' "$d.filtered" | tr '\n' ' ')"
   done
   ```

   Reading the summary lines:

   | summary | meaning | action |
   |---|---|---|
   | `0 sources` | the filter killed no feed or sitemap | nothing (`domain_report` shows whether the domain is dormant) |
   | a few feeds / news sitemaps / indexes | a real news source | `filtered_sources update <domain>` |
   | only `unflagged` | seeds blocked before their first fetch, or false positives | read the file, `update` with TYPES `unflagged` or `reset_url` per URL |
   | thousands of plain sitemaps or indexes, no feed, no news sitemap | a sitemap fan-out (catalogues, archives, per-channel sitemaps) | do **not** re-arm; consider a `Host` or `DenyPath` rule instead |
   | few real feeds among many look-alikes (e.g. feedburner) | | `update ... '.*' feed` |

3. **Re-arm** with `filtered_sources update`, narrowed with URL-REGEXP and TYPES where
   needed. Examples from a real cleanup:

   ```sh
   # one feed per Blogger blog: the RSS variant of the posts feed
   bin/status filtered_sources update blogspot.com 'https?://[^/]+\.blogspot\.com/feeds/posts/default\?alt=rss' feed
   # news sitemaps hosted on S3, ignoring 3000 catalogue sitemaps
   bin/status filtered_sources update amazonaws.com '.*' feed,sitemap-news
   # the sitemap index and the news sitemap, not the 4000 per-channel sub-sitemaps
   REFETCH_DATE=2000-01-01 bin/status filtered_sources update brighteon.com '.*/(channel-)?sitemap\.xml'
   ```

4. **Verify** after 15 to 30 minutes:

   ```sh
   bin/status filtered_sources count example.com      # 0 right after the update
   bin/status refetch_sources search example.com | sed '/^--/q' | column -t -s $'\t'
   bin/status domain_report example.com
   ```

   Good: STATUS `FETCHED`, LAST_FETCH minutes old, NEXT_FETCH in the future, LINKS > 0,
   and DISCOVERED growing in `domain_report` as articles arrive. Bad outcomes are readable
   in ERROR/REDIRECT: `robots.txt` (the site forbids it), a redirect target (the source
   moved; the target was discovered instead), `Filtered` again (the filter still applies),
   FETCH_ERROR (server did not answer; retried after 48 h, three times).

**NOTE**: Re-fetching a sitemap index does not revive its (sitemaps) children. DISCOVERED URLs are
  indexed with a `create` operation, which never overwrites an existing document. ERROR
  children stay ERROR until their own retry date or a manual re-arm.
