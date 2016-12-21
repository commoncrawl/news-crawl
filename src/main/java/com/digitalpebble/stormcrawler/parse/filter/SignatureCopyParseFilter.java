/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.parse.filter;

import java.util.Map;

import org.w3c.dom.DocumentFragment;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.parse.ParseData;
import com.digitalpebble.stormcrawler.parse.ParseFilter;
import com.digitalpebble.stormcrawler.parse.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Adds a copy of the current signature to metadata.
 */
public class SignatureCopyParseFilter extends ParseFilter {

    private String signatureKeyName = "signature";
    private String signatureCopyKeyName = "signatureOld";

    @Override
    public void filter(String URL, byte[] content, DocumentFragment doc,
            ParseResult parse) {
        ParseData parseData = parse.get(URL);
        Metadata metadata = parseData.getMetadata();
        String signature = metadata.getFirstValue(signatureKeyName);
        if (signature != null)
            metadata.setValue(signatureCopyKeyName, signature);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void configure(Map stormConf, JsonNode filterParams) {
        JsonNode node = filterParams.get("keyName");
        if (node != null && node.isTextual()) {
            signatureKeyName = node.asText(signatureKeyName);
        }
        node = filterParams.get("keyNameCopy");
        if (node != null && node.isTextual()) {
            signatureCopyKeyName = node.asText(signatureCopyKeyName);
        }
    }

}
