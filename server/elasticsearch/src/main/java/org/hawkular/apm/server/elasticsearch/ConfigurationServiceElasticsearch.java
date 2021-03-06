/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.apm.server.elasticsearch;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.admin.indices.refresh.RefreshRequestBuilder;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.hawkular.apm.api.model.Severity;
import org.hawkular.apm.api.model.config.CollectorConfiguration;
import org.hawkular.apm.api.model.config.btxn.BusinessTxnConfig;
import org.hawkular.apm.api.model.config.btxn.BusinessTxnSummary;
import org.hawkular.apm.api.model.config.btxn.ConfigMessage;
import org.hawkular.apm.api.services.AbstractConfigurationService;
import org.hawkular.apm.api.services.ConfigurationLoader;
import org.hawkular.apm.server.elasticsearch.log.MsgLogger;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class provides the Elasticsearch implementation of the Administration
 * Service.
 *
 * @author gbrown
 */
public class ConfigurationServiceElasticsearch extends AbstractConfigurationService {

    private final MsgLogger msgLog = MsgLogger.LOGGER;

    private static final String BUSINESS_TXN_CONFIG_TYPE = "businesstxnconfig";

    private static final String BUSINESS_TXN_CONFIG_INVALID_TYPE = "businesstxnconfiginvalid";

    private static final ObjectMapper mapper = new ObjectMapper();

    private ElasticsearchClient client = ElasticsearchClient.getSingleton();

    private static int DEFAULT_RESPONSE_SIZE = 100000;

    private static long DEFAULT_TIMEOUT = 10000L;

    private long timeout = DEFAULT_TIMEOUT;

    private int maxResponseSize = DEFAULT_RESPONSE_SIZE;

    private final Clock clock;

    public ConfigurationServiceElasticsearch(Clock clock) {
        super();
        this.clock = clock;
    }

    public ConfigurationServiceElasticsearch() {
        super();
        this.clock = Clock.systemDefaultZone();
    }

    /**
     * This method gets the elasticsearch client.
     *
     * @return The elasticsearch client
     */
    public ElasticsearchClient getElasticsearchClient() {
        return client;
    }

    /**
     * This method sets the elasticsearch client.
     *
     * @param client The elasticsearch client
     */
    public void setElasticsearchClient(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public CollectorConfiguration getCollector(String tenantId, String type, String host, String server) {
        CollectorConfiguration config = ConfigurationLoader.getConfiguration(type);

        try {
            String index = client.getIndex(tenantId);

            RefreshRequestBuilder refreshRequestBuilder =
                    client.getClient().admin().indices().prepareRefresh(index);
            client.getClient().admin().indices().refresh(refreshRequestBuilder.request()).actionGet();

            // Only retrieve valid configurations
            SearchResponse response = client.getClient().prepareSearch(index)
                    .setTypes(BUSINESS_TXN_CONFIG_TYPE)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setTimeout(TimeValue.timeValueMillis(timeout))
                    .setSize(maxResponseSize)
                    .setQuery(QueryBuilders.matchAllQuery()).execute().actionGet();
            if (response.isTimedOut()) {
                msgLog.warnQueryTimedOut();
            }

            for (SearchHit searchHitFields : response.getHits()) {
                try {
                    BusinessTxnConfig btc = mapper.readValue(searchHitFields.getSourceAsString(),
                            BusinessTxnConfig.class);
                    if (!btc.isDeleted()) {
                        config.getBusinessTransactions().put(searchHitFields.getId(), btc);
                    }
                } catch (Exception e) {
                    msgLog.errorFailedToParse(e);
                }
            }
        } catch (org.elasticsearch.indices.IndexMissingException t) {
            // Ignore, as means that no business transaction configurations have
            // been stored yet
            if (msgLog.isTraceEnabled()) {
                msgLog.tracef("No index found, so unable to retrieve business transaction configs");
            }
        }

        return config;
    }

    @Override
    public List<ConfigMessage> setBusinessTransaction(String tenantId, String name, BusinessTxnConfig config)
            throws Exception {
        if (msgLog.isTraceEnabled()) {
            msgLog.tracef("Update business transaction config with name[%s] config=%s", name, config);
        }

        List<ConfigMessage> messages = validateBusinessTransaction(config);

        // Set last updated time
        config.setLastUpdated(clock.millis());

        String index = (messages.isEmpty() ? BUSINESS_TXN_CONFIG_TYPE : BUSINESS_TXN_CONFIG_INVALID_TYPE);

        IndexRequestBuilder builder = client.getClient().prepareIndex(client.getIndex(tenantId),
                index, name).setRouting(name)
                .setSource(mapper.writeValueAsString(config));

        builder.execute().actionGet();

        if (messages.isEmpty()) {
            ConfigMessage cm = new ConfigMessage();
            cm.setSeverity(Severity.Info);
            cm.setMessage("Configuration successfully published");
            messages.add(cm);

            // Delete any invalid entry
            DeleteRequestBuilder deletion = client.getClient().prepareDelete(client.getIndex(tenantId),
                    BUSINESS_TXN_CONFIG_INVALID_TYPE, name);

            deletion.execute().actionGet();

        } else {
            ConfigMessage cm = new ConfigMessage();
            cm.setSeverity(Severity.Warning);
            cm.setMessage("Configuration has not been published due to previous errors and/or warnings");
            messages.add(cm);
        }

        return messages;
    }

    @Override
    public BusinessTxnConfig getBusinessTransaction(String tenantId, String name) {
        BusinessTxnConfig ret = null;

        if (msgLog.isTraceEnabled()) {
            msgLog.tracef("Get business transaction config with name[%s]", name);
        }

        try {
            String index = client.getIndex(tenantId);

            RefreshRequestBuilder refreshRequestBuilder =
                    client.getClient().admin().indices().prepareRefresh(index);
            client.getClient().admin().indices().refresh(refreshRequestBuilder.request()).actionGet();

            // First check if an invalid config exists
            GetResponse response = client.getClient().prepareGet(
                    index, BUSINESS_TXN_CONFIG_INVALID_TYPE, name).setRouting(name)
                    .execute()
                    .actionGet();

            if (response.isSourceEmpty()) {
                // Retrieve the valid configuration
                response = client.getClient().prepareGet(
                        index, BUSINESS_TXN_CONFIG_TYPE, name).setRouting(name)
                        .execute()
                        .actionGet();
            }

            if (!response.isSourceEmpty()) {
                try {
                    ret = mapper.readValue(response.getSourceAsString(), BusinessTxnConfig.class);

                    // Check if config was deleted
                    if (ret.isDeleted()) {
                        ret = null;
                    }
                } catch (Exception e) {
                    msgLog.errorFailedToParse(e);
                }
            }

        } catch (org.elasticsearch.indices.IndexMissingException t) {
            // Ignore, as means that no business transaction configurations have
            // been stored yet
            if (msgLog.isTraceEnabled()) {
                msgLog.tracef("No index found, so unable to retrieve business transaction config [%s]", name);
            }
        }

        if (msgLog.isTraceEnabled()) {
            msgLog.tracef("Get business transaction config with name[%s] is: %s", name, ret);
        }

        return ret;
    }

    @Override
    public List<BusinessTxnSummary> getBusinessTransactionSummaries(String tenantId) {
        List<BusinessTxnSummary> ret = new ArrayList<BusinessTxnSummary>();
        String index = client.getIndex(tenantId);

        try {
            RefreshRequestBuilder refreshRequestBuilder =
                    client.getClient().admin().indices().prepareRefresh(index);
            client.getClient().admin().indices().refresh(refreshRequestBuilder.request()).actionGet();

            SearchResponse response = client.getClient().prepareSearch(index)
                    .setTypes(BUSINESS_TXN_CONFIG_TYPE)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setTimeout(TimeValue.timeValueMillis(timeout))
                    .setSize(maxResponseSize)
                    .setQuery(QueryBuilders.matchAllQuery()).execute().actionGet();
            if (response.isTimedOut()) {
                msgLog.warnQueryTimedOut();
            }

            List<String> names = new ArrayList<String>();

            for (SearchHit searchHitFields : response.getHits()) {
                try {
                    BusinessTxnConfig config = mapper.readValue(searchHitFields.getSourceAsString(),
                            BusinessTxnConfig.class);
                    if (!config.isDeleted()) {
                        BusinessTxnSummary summary = new BusinessTxnSummary();
                        summary.setName(searchHitFields.getId());
                        summary.setDescription(config.getDescription());
                        summary.setLevel(config.getLevel());
                        ret.add(summary);
                        names.add(summary.getName());
                    }
                } catch (Exception e) {
                    msgLog.errorFailedToParse(e);
                }
            }

            // Check whether any invalid business transactions exist that have not yet
            // been stored in the valid list
            response = client.getClient().prepareSearch(index)
                    .setTypes(BUSINESS_TXN_CONFIG_INVALID_TYPE)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setTimeout(TimeValue.timeValueMillis(timeout))
                    .setSize(maxResponseSize)
                    .setQuery(QueryBuilders.matchAllQuery()).execute().actionGet();
            if (response.isTimedOut()) {
                msgLog.warnQueryTimedOut();
            }

            for (SearchHit searchHitFields : response.getHits()) {
                try {
                    BusinessTxnConfig config = mapper.readValue(searchHitFields.getSourceAsString(),
                            BusinessTxnConfig.class);
                    if (!names.contains(searchHitFields.getId())) {
                        BusinessTxnSummary summary = new BusinessTxnSummary();
                        summary.setName(searchHitFields.getId());
                        summary.setDescription(config.getDescription());
                        summary.setLevel(config.getLevel());
                        ret.add(summary);
                    }
                } catch (Exception e) {
                    msgLog.errorFailedToParse(e);
                }
            }
        } catch (org.elasticsearch.indices.IndexMissingException t) {
            // Ignore, as means that no business transaction configurations have
            // been stored yet
            if (msgLog.isTraceEnabled()) {
                msgLog.tracef("No index found, so unable to retrieve business transaction summaries");
            }
        }

        return ret;
    }

    @Override
    public Map<String, BusinessTxnConfig> getBusinessTransactions(String tenantId, long updated) {
        Map<String, BusinessTxnConfig> ret = new HashMap<String, BusinessTxnConfig>();
        String index = client.getIndex(tenantId);

        try {
            RefreshRequestBuilder refreshRequestBuilder =
                    client.getClient().admin().indices().prepareRefresh(index);
            client.getClient().admin().indices().refresh(refreshRequestBuilder.request()).actionGet();

            // Should only obtain valid business transactions
            SearchResponse response = client.getClient().prepareSearch(index)
                    .setTypes(BUSINESS_TXN_CONFIG_TYPE)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setTimeout(TimeValue.timeValueMillis(timeout))
                    .setSize(maxResponseSize)
                    .setQuery(QueryBuilders.matchAllQuery()).execute().actionGet();
            if (response.isTimedOut()) {
                msgLog.warnQueryTimedOut();
            }

            for (SearchHit searchHitFields : response.getHits()) {
                try {
                    BusinessTxnConfig btxn = mapper.readValue(searchHitFields.getSourceAsString(),
                            BusinessTxnConfig.class);
                    if ((updated == 0 && !btxn.isDeleted()) || (updated > 0 && btxn.getLastUpdated() > updated)) {
                        ret.put(searchHitFields.getId(), btxn);
                    }
                } catch (Exception e) {
                    msgLog.errorFailedToParse(e);
                }
            }
        } catch (org.elasticsearch.indices.IndexMissingException t) {
            // Ignore, as means that no business transaction configurations have
            // been stored yet
            if (msgLog.isTraceEnabled()) {
                msgLog.tracef("No index found, so unable to retrieve business transaction names");
            }
        }

        return ret;
    }

    @Override
    public void removeBusinessTransaction(String tenantId, String name) throws Exception {
        BusinessTxnConfig config = new BusinessTxnConfig();
        config.setDeleted(true);
        config.setLastUpdated(clock.millis());

        // Remove valid version of the business transaction config
        IndexRequestBuilder builder = client.getClient().prepareIndex(client.getIndex(tenantId),
                BUSINESS_TXN_CONFIG_TYPE, name).setRouting(name)
                .setSource(mapper.writeValueAsString(config));

        builder.execute().actionGet();

        // Remove invalid version of the business transaction config, which may or may not exist
        DeleteRequestBuilder deletion = client.getClient().prepareDelete(client.getIndex(tenantId),
                BUSINESS_TXN_CONFIG_INVALID_TYPE, name);

        deletion.execute().actionGet();

        if (msgLog.isTraceEnabled()) {
            msgLog.tracef("Remove business transaction config with name[%s]", name);
        }
    }

    @Override
    public void clear(String tenantId) {
        client.clearTenant(tenantId);
    }

}
