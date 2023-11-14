/**
 * Licensed to DigitalPebble Ltd under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commoncrawl.stormcrawler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.commoncrawl.stormcrawler.filter.FastURLFilter;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.filtering.URLFilter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class FastURLFilterTest {

    protected static URLFilter filter;

    @BeforeClass
    public static void init() {
	filter = createFilter("fast-urlfilter.txt");
    }

    public static FastURLFilter createFilter(String fileName) {
	ObjectNode filterParams = new ObjectNode(JsonNodeFactory.instance);
	filterParams.put("file", fileName);
	FastURLFilter filter = new FastURLFilter();
	Map<String, Object> conf = new HashMap<>();
	conf.put("fast.urlfilter.refresh", 10);
	filter.configure(conf, filterParams);
	return filter;
    }

    @Test
    public void testHostFilter() throws MalformedURLException {
	URL url = new URL("http://may.go.com/image.jpg");
	Metadata metadata = new Metadata();
	String filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(url.toString(), filterResult);
	
	url = new URL("http://no.go.com/");
	filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(null, filterResult);
    }

    @Test
    public void testDomainNotAllowed() throws MalformedURLException {
	URL url = new URL("http://domainnotallowed.com/forum/search.php");
	Metadata metadata = new Metadata();
	String filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(null, filterResult);
	
	url = new URL("http://domainnotallowed.com/");
	filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(null, filterResult);
	
	url = new URL("http://partiallyallowed.com/");
	filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(url.toString(), filterResult);
	
	url = new URL("http://partiallyallowed.com/verbotten");
	filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(null, filterResult);

	// allowed
	url = new URL("http://digitalpebble.com/");
	filterResult = filter.filter(url, metadata, url.toExternalForm());
	Assert.assertEquals(url.toString(), filterResult);
    }
}
