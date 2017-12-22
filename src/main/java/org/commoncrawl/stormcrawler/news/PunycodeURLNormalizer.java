package org.commoncrawl.stormcrawler.news;

import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.filtering.URLFilter;
import com.fasterxml.jackson.databind.JsonNode;

public class PunycodeURLNormalizer implements URLFilter {

    @Override
    public void configure(Map stormConf, JsonNode filterParams) {
    }

    private boolean isAscii(String str) {
        char[] chars = str.toCharArray();
        for (char c : chars) {
            if (c > 127) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String filter(URL sourceUrl, Metadata sourceMetadata,
            String urlToFilter) {
        try {
            URL url = new URL(urlToFilter);
            String hostName = url.getHost();
            if (isAscii(hostName)) {
                return urlToFilter;
            }
            hostName = IDN.toASCII(url.getHost());
            if (hostName.equals(url.getHost())) {
                return urlToFilter;
            }
            urlToFilter = new URL(url.getProtocol(), hostName, url.getPort(),
                    url.getFile()).toString();
        } catch (MalformedURLException e) {
            return null;
        }
        return urlToFilter;
    }

}
