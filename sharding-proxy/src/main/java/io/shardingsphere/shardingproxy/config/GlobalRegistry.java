/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.shardingproxy.config;

import com.google.common.base.Strings;
import com.google.common.eventbus.Subscribe;
import io.shardingsphere.core.constant.properties.ShardingProperties;
import io.shardingsphere.core.constant.properties.ShardingPropertiesConstant;
import io.shardingsphere.core.constant.transaction.TransactionType;
import io.shardingsphere.core.event.ShardingEventBusInstance;
import io.shardingsphere.core.executor.ShardingExecuteEngine;
import io.shardingsphere.core.rule.Authentication;
import io.shardingsphere.core.rule.DataSourceParameter;
import io.shardingsphere.core.yaml.YamlRuleConfiguration;
import io.shardingsphere.core.yaml.other.YamlServerConfiguration;
import io.shardingsphere.orchestration.internal.event.config.ProxyConfigurationEventBusEvent;
import io.shardingsphere.orchestration.internal.event.state.CircuitStateEventBusEvent;
import io.shardingsphere.orchestration.internal.event.state.ProxyDisabledStateEventBusEvent;
import io.shardingsphere.shardingproxy.config.nio.BackendNIOConfiguration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry.
 *
 * @author chenqingyang
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public final class GlobalRegistry {
    
    private static final GlobalRegistry INSTANCE = new GlobalRegistry();
    
    private List<String> schemaNames = new LinkedList<>();
    
    private Map<String, RuleInstance> ruleInstanceMap = new ConcurrentHashMap<>();
    
    private Authentication authentication;
    
    private boolean showSQL;
    
    private int maxConnectionsSizePerQuery;
    
    private int acceptorSize;
    
    private int executorSize;
    
    private TransactionType transactionType;
    
    private boolean openTracingEnable;
    
    private boolean useNIO;
    
    private BackendNIOConfiguration backendNIOConfig;
    
    private boolean isCircuitBreak;
    
    /**
     * Get instance of proxy context.
     *
     * @return instance of proxy context.
     */
    public static GlobalRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Register listener.
     */
    public void register() {
        ShardingEventBusInstance.getInstance().register(this);
    }
    
    /**
     * Initialize proxy context.
     *
     * @param serverConfig server configuration
     * @param schemaDataSources data source map
     * @param schemaRules schema rule map
     */
    public void init(final YamlServerConfiguration serverConfig, final Map<String, Map<String, DataSourceParameter>> schemaDataSources, final Map<String, YamlRuleConfiguration> schemaRules) {
        initServerConfiguration(serverConfig);
        for (Entry<String, YamlRuleConfiguration> entry : schemaRules.entrySet()) {
            String schemaName = entry.getKey();
            schemaNames.add(schemaName);
            ruleInstanceMap.put(schemaName, new RuleInstance(schemaName, schemaDataSources.get(schemaName), entry.getValue()));
        }
    }
    
    private void initServerConfiguration(final YamlServerConfiguration serverConfig) {
        Properties properties = serverConfig.getProps();
        ShardingProperties shardingProperties = new ShardingProperties(null == properties ? new Properties() : properties);
        maxConnectionsSizePerQuery = shardingProperties.getValue(ShardingPropertiesConstant.MAX_CONNECTIONS_SIZE_PER_QUERY);
        // TODO just config proxy.transaction.enable here, in future(3.1.0)
        transactionType = shardingProperties.<Boolean>getValue(ShardingPropertiesConstant.PROXY_TRANSACTION_ENABLED) ? TransactionType.XA : TransactionType.LOCAL;
        openTracingEnable = shardingProperties.<Boolean>getValue(ShardingPropertiesConstant.PROXY_OPENTRACING_ENABLED);
        showSQL = shardingProperties.getValue(ShardingPropertiesConstant.SQL_SHOW);
        acceptorSize = shardingProperties.getValue(ShardingPropertiesConstant.ACCEPTOR_SIZE);
        executorSize = shardingProperties.getValue(ShardingPropertiesConstant.EXECUTOR_SIZE);
        // TODO :jiaqi force off use NIO for backend, this feature is not complete yet
        useNIO = false;
        // boolean proxyBackendUseNio = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_USE_NIO);
        int databaseConnectionCount = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_MAX_CONNECTIONS);
        int connectionTimeoutSeconds = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_CONNECTION_TIMEOUT_SECONDS);
        backendNIOConfig = new BackendNIOConfiguration(databaseConnectionCount, connectionTimeoutSeconds);
        authentication = serverConfig.getAuthentication();
    }
    
    /**
     * Initialize sharding meta data.
     *
     * @param executeEngine sharding execute engine
     */
    public void initShardingMetaData(final ShardingExecuteEngine executeEngine) {
        for (RuleInstance each : ruleInstanceMap.values()) {
            each.initShardingMetaData(executeEngine);
        }
    }
    
    /**
     * Check schema exists.
     *
     * @param schema schema
     * @return schema exists or not
     */
    public boolean schemaExists(final String schema) {
        return schemaNames.contains(schema);
    }
    
    /**
     * Get rule instance of schema.
     *
     * @param schema schema
     * @return rule instance of schema
     */
    public RuleInstance getRuleInstance(final String schema) {
        return Strings.isNullOrEmpty(schema) ? null : ruleInstanceMap.get(schema);
    }
    
    /**
     * Renew proxy configuration.
     *
     * @param proxyConfigurationEventBusEvent proxy event bus event.
     */
    @Subscribe
    public void renew(final ProxyConfigurationEventBusEvent proxyConfigurationEventBusEvent) {
        initServerConfiguration(proxyConfigurationEventBusEvent.getServerConfiguration());
        for (Entry<String, RuleInstance> entry : ruleInstanceMap.entrySet()) {
            entry.getValue().getBackendDataSource().close();
        }
        ruleInstanceMap.clear();
        for (Entry<String, Map<String, DataSourceParameter>> entry : proxyConfigurationEventBusEvent.getSchemaDataSourceMap().entrySet()) {
            String schemaName = entry.getKey();
            ruleInstanceMap.put(schemaName, new RuleInstance(schemaName, entry.getValue(), proxyConfigurationEventBusEvent.getSchemaRuleMap().get(schemaName)));
        }
    }
    
    /**
     * Renew circuit breaker dataSource names.
     *
     * @param circuitStateEventBusEvent jdbc circuit event bus event
     */
    @Subscribe
    public void renewCircuitBreakerDataSourceNames(final CircuitStateEventBusEvent circuitStateEventBusEvent) {
        isCircuitBreak = circuitStateEventBusEvent.isCircuitBreak();
    }
    
    /**
     * Renew disabled data source names.
     *
     * @param disabledStateEventBusEvent jdbc disabled event bus event
     */
    @Subscribe
    public void renewDisabledDataSourceNames(final ProxyDisabledStateEventBusEvent disabledStateEventBusEvent) {
        for (Entry<String, RuleInstance> entry : ruleInstanceMap.entrySet()) {
            entry.getValue().setDisabledDataSourceNames(disabledStateEventBusEvent.getDisabledSchemaDataSourceMap().get(entry.getKey()));
        }
    }
}
