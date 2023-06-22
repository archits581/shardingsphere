/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

package org.apache.shardingsphere.encrypt.rule;

import lombok.Getter;
import org.apache.shardingsphere.encrypt.api.config.CompatibleEncryptRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.EncryptRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.rule.EncryptColumnRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.rule.EncryptTableRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.context.EncryptContext;
import org.apache.shardingsphere.encrypt.api.encrypt.assisted.AssistedEncryptAlgorithm;
import org.apache.shardingsphere.encrypt.api.encrypt.like.LikeEncryptAlgorithm;
import org.apache.shardingsphere.encrypt.api.encrypt.standard.StandardEncryptAlgorithm;
import org.apache.shardingsphere.encrypt.context.EncryptContextBuilder;
import org.apache.shardingsphere.encrypt.exception.algorithm.MismatchedEncryptAlgorithmTypeException;
import org.apache.shardingsphere.encrypt.exception.metadata.EncryptTableNotFoundException;
import org.apache.shardingsphere.encrypt.exception.metadata.MissingEncryptorException;
import org.apache.shardingsphere.encrypt.rule.column.EncryptColumn;
import org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.infra.rule.identifier.scope.DatabaseRule;
import org.apache.shardingsphere.infra.rule.identifier.type.TableContainedRule;
import org.apache.shardingsphere.infra.rule.identifier.type.TableNamesMapper;
import org.apache.shardingsphere.infra.util.exception.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.util.spi.type.typed.TypedSPILoader;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Encrypt rule.
 */
public final class EncryptRule implements DatabaseRule, TableContainedRule {
    
    @Getter
    private final RuleConfiguration configuration;
    
    @SuppressWarnings("rawtypes")
    private final Map<String, StandardEncryptAlgorithm> standardEncryptors = new LinkedHashMap<>();
    
    @SuppressWarnings("rawtypes")
    private final Map<String, AssistedEncryptAlgorithm> assistedEncryptors = new LinkedHashMap<>();
    
    @SuppressWarnings("rawtypes")
    private final Map<String, LikeEncryptAlgorithm> likeEncryptors = new LinkedHashMap<>();
    
    private final Map<String, EncryptTable> tables = new LinkedHashMap<>();
    
    private final TableNamesMapper tableNamesMapper = new TableNamesMapper();
    
    public EncryptRule(final EncryptRuleConfiguration ruleConfig) {
        configuration = ruleConfig;
        ruleConfig.getEncryptors().forEach((key, value) -> putAllEncryptors(key, TypedSPILoader.getService(EncryptAlgorithm.class, value.getType(), value.getProps())));
        for (EncryptTableRuleConfiguration each : ruleConfig.getTables()) {
            each.getColumns().forEach(this::checkEncryptAlgorithmType);
            tables.put(each.getName().toLowerCase(), new EncryptTable(each, standardEncryptors, assistedEncryptors, likeEncryptors));
            tableNamesMapper.put(each.getName());
        }
    }
    
    /**
     * Encrypt rule constructor.
     * 
     * @deprecated deprecated by compatible encrypt rule configuration
     */
    @Deprecated
    public EncryptRule(final CompatibleEncryptRuleConfiguration ruleConfig) {
        configuration = ruleConfig;
        ruleConfig.getEncryptors().forEach((key, value) -> putAllEncryptors(key, TypedSPILoader.getService(EncryptAlgorithm.class, value.getType(), value.getProps())));
        for (EncryptTableRuleConfiguration each : ruleConfig.getTables()) {
            each.getColumns().forEach(this::checkEncryptAlgorithmType);
            tables.put(each.getName().toLowerCase(), new EncryptTable(each, standardEncryptors, assistedEncryptors, likeEncryptors));
            tableNamesMapper.put(each.getName());
        }
    }
    
    @SuppressWarnings("rawtypes")
    private void putAllEncryptors(final String encryptorName, final EncryptAlgorithm algorithm) {
        if (algorithm instanceof StandardEncryptAlgorithm) {
            standardEncryptors.put(encryptorName, (StandardEncryptAlgorithm) algorithm);
        }
        if (algorithm instanceof AssistedEncryptAlgorithm) {
            assistedEncryptors.put(encryptorName, (AssistedEncryptAlgorithm) algorithm);
        }
        if (algorithm instanceof LikeEncryptAlgorithm) {
            likeEncryptors.put(encryptorName, (LikeEncryptAlgorithm) algorithm);
        }
    }
    
    private void checkEncryptAlgorithmType(final EncryptColumnRuleConfiguration columnRuleConfig) {
        ShardingSpherePreconditions.checkState(standardEncryptors.containsKey(columnRuleConfig.getCipher().getEncryptorName()),
                () -> new MismatchedEncryptAlgorithmTypeException("Cipher", columnRuleConfig.getCipher().getEncryptorName(), StandardEncryptAlgorithm.class.getSimpleName()));
        columnRuleConfig.getLikeQuery().ifPresent(optional -> ShardingSpherePreconditions.checkState(likeEncryptors.containsKey(optional.getEncryptorName()),
                () -> new MismatchedEncryptAlgorithmTypeException("Like query", optional.getEncryptorName(), LikeEncryptAlgorithm.class.getSimpleName())));
        columnRuleConfig.getAssistedQuery().ifPresent(optional -> ShardingSpherePreconditions.checkState(assistedEncryptors.containsKey(optional.getEncryptorName()),
                () -> new MismatchedEncryptAlgorithmTypeException("Assisted query", optional.getEncryptorName(), AssistedEncryptAlgorithm.class.getSimpleName())));
    }
    
    /**
     * Find encrypt table.
     * 
     * @param tableName table name
     * @return encrypt table
     */
    public Optional<EncryptTable> findEncryptTable(final String tableName) {
        return Optional.ofNullable(tables.get(tableName.toLowerCase()));
    }
    
    /**
     * Get encrypt table.
     *
     * @param tableName table name
     * @return encrypt table
     */
    public EncryptTable getEncryptTable(final String tableName) {
        Optional<EncryptTable> encryptTable = findEncryptTable(tableName);
        ShardingSpherePreconditions.checkState(encryptTable.isPresent(), () -> new EncryptTableNotFoundException(tableName));
        return encryptTable.get();
    }
    
    /**
     * Encrypt.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValue original value
     * @return encrypted value
     */
    public Object encrypt(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final Object originalValue) {
        EncryptColumn encryptColumn = getEncryptTable(tableName).getEncryptColumn(logicColumnName);
        return encryptColumn.getCipher().encrypt(databaseName, schemaName, tableName, logicColumnName, originalValue);
    }
    
    /**
     * Encrypt.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValues original values
     * @return encrypted values
     */
    public List<Object> encrypt(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final List<Object> originalValues) {
        EncryptColumn encryptColumn = getEncryptTable(tableName).getEncryptColumn(logicColumnName);
        return encryptColumn.getCipher().encrypt(databaseName, schemaName, tableName, logicColumnName, originalValues);
    }
    
    /**
     * Decrypt.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param cipherValue cipher value
     * @return decrypted value
     */
    public Object decrypt(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final Object cipherValue) {
        EncryptColumn encryptColumn = getEncryptTable(tableName).getEncryptColumn(logicColumnName);
        return encryptColumn.getCipher().decrypt(databaseName, schemaName, tableName, logicColumnName, cipherValue);
    }
    
    /**
     * Get encrypt assisted query value.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValue original value
     * @return assisted query values
     */
    @SuppressWarnings("unchecked")
    public Object getEncryptAssistedQueryValue(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final Object originalValue) {
        if (null == originalValue) {
            return null;
        }
        EncryptContext context = EncryptContextBuilder.build(databaseName, schemaName, tableName, logicColumnName);
        return getAssistedQueryEncryptor(tableName, logicColumnName).encrypt(originalValue, context);
    }
    
    /**
     * Get encrypt assisted query values.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValues original values
     * @return assisted query values
     */
    public List<Object> getEncryptAssistedQueryValues(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final List<Object> originalValues) {
        EncryptContext context = EncryptContextBuilder.build(databaseName, schemaName, tableName, logicColumnName);
        return getEncryptAssistedQueryValues(getAssistedQueryEncryptor(tableName, logicColumnName), originalValues, context);
    }
    
    @SuppressWarnings("unchecked")
    private List<Object> getEncryptAssistedQueryValues(@SuppressWarnings("rawtypes") final AssistedEncryptAlgorithm assistedQueryEncryptor,
                                                       final List<Object> originalValues, final EncryptContext context) {
        List<Object> result = new LinkedList<>();
        for (Object each : originalValues) {
            result.add(null == each ? null : assistedQueryEncryptor.encrypt(each, context));
        }
        return result;
    }
    
    @SuppressWarnings("rawtypes")
    private AssistedEncryptAlgorithm getAssistedQueryEncryptor(final String tableName, final String logicColumnName) {
        return findEncryptTable(tableName).flatMap(optional -> optional.findAssistedQueryEncryptor(logicColumnName))
                .orElseThrow(() -> new MissingEncryptorException(tableName, logicColumnName, "ASSIST_QUERY"));
    }
    
    /**
     * Get encrypt like query value.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValue original value
     * @return like query values
     */
    @SuppressWarnings("unchecked")
    public Object getEncryptLikeQueryValue(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final Object originalValue) {
        if (null == originalValue) {
            return null;
        }
        EncryptContext context = EncryptContextBuilder.build(databaseName, schemaName, tableName, logicColumnName);
        return getLikeQueryEncryptor(tableName, logicColumnName).encrypt(originalValue, context);
    }
    
    /**
     * Get encrypt like query values.
     *
     * @param databaseName database name
     * @param schemaName schema name
     * @param tableName table name
     * @param logicColumnName logic column name
     * @param originalValues original values
     * @return like query values
     */
    public List<Object> getEncryptLikeQueryValues(final String databaseName, final String schemaName, final String tableName, final String logicColumnName, final List<Object> originalValues) {
        EncryptContext context = EncryptContextBuilder.build(databaseName, schemaName, tableName, logicColumnName);
        return getEncryptLikeQueryValues(getLikeQueryEncryptor(tableName, logicColumnName), originalValues, context);
    }
    
    @SuppressWarnings("unchecked")
    private List<Object> getEncryptLikeQueryValues(@SuppressWarnings("rawtypes") final LikeEncryptAlgorithm likeQueryEncryptor, final List<Object> originalValues, final EncryptContext context) {
        List<Object> result = new LinkedList<>();
        for (Object each : originalValues) {
            result.add(null == each ? null : likeQueryEncryptor.encrypt(each, context));
        }
        return result;
    }
    
    @SuppressWarnings("rawtypes")
    private LikeEncryptAlgorithm getLikeQueryEncryptor(final String tableName, final String logicColumnName) {
        return findEncryptTable(tableName).flatMap(optional -> optional.findLikeQueryEncryptor(logicColumnName))
                .orElseThrow(() -> new MissingEncryptorException(tableName, logicColumnName, "LIKE_QUERY"));
    }
    
    @Override
    public TableNamesMapper getLogicTableMapper() {
        return tableNamesMapper;
    }
    
    @Override
    public TableNamesMapper getActualTableMapper() {
        return new TableNamesMapper();
    }
    
    @Override
    public TableNamesMapper getDistributedTableMapper() {
        return new TableNamesMapper();
    }
    
    @Override
    public TableNamesMapper getEnhancedTableMapper() {
        return getLogicTableMapper();
    }
    
    @Override
    public String getType() {
        return EncryptRule.class.getSimpleName();
    }
}
