/*
 * Copyright 2014, Tuplejump Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tuplejump.stargate.cassandra;

import com.tuplejump.stargate.Fields;
import com.tuplejump.stargate.lucene.LuceneUtils;
import com.tuplejump.stargate.lucene.Options;
import com.tuplejump.stargate.lucene.Properties;
import com.tuplejump.stargate.lucene.query.function.Tuple;
import com.tuplejump.stargate.utils.Pair;
import org.apache.cassandra.config.*;
import org.apache.cassandra.cql3.CFDefinition;
import org.apache.cassandra.cql3.CQL3Type;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.queryparser.flexible.standard.config.NumericConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Utilities to read Cassandra configuration
 */
public class CassandraUtils {
    private static final Logger logger = LoggerFactory.getLogger(CassandraUtils.class);
    private final static String DEFAULT_CONFIGURATION = "cassandra.yaml";
    private static Config conf;


    public static String[] getDataDirs() throws IOException, ConfigurationException {
        if (conf == null) {
            loadYaml();
        }
        return conf.data_file_directories;
    }


    static URL getStorageConfigURL() throws ConfigurationException {
        String configUrl = System.getProperty("cassandra.config");
        if (configUrl == null)
            configUrl = DEFAULT_CONFIGURATION;

        URL url;
        try {
            url = new URL(configUrl);
            url.openStream().close(); // catches well-formed but bogus URLs
        } catch (Exception e) {
            ClassLoader loader = DatabaseDescriptor.class.getClassLoader();
            url = loader.getResource(configUrl);
            if (url == null)
                throw new ConfigurationException("Cannot locate " + configUrl);
        }

        return url;
    }

    static void loadYaml() throws ConfigurationException, IOException {
        URL url = CassandraUtils.getStorageConfigURL();
        logger.info("Loading settings from " + url);
        String loaderClass = System.getProperty("cassandra.config.loader");
        ConfigurationLoader loader = loaderClass == null
                ? new YamlConfigurationLoader()
                : FBUtilities.<ConfigurationLoader>construct(loaderClass, "configuration loading");
        conf = loader.loadConfig();
        logger.info("Data files directories: " + Arrays.toString(conf.data_file_directories));
    }

    public static Options getOptions(String columnName, ColumnFamilyStore baseCfs, String json) {
        try {
            Properties mapping = Options.inputMapper.readValue(json, Properties.class);
            return getOptions(mapping, baseCfs, columnName);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Options getOptions(Properties mapping, ColumnFamilyStore baseCfs, String colName) {
        Properties primary = mapping;
        String defaultField = colName;
        Map<String, NumericConfig> numericFieldOptions = new HashMap<>();
        Map<String, FieldType> fieldDocValueTypes = new TreeMap<>();
        Map<String, FieldType> collectionFieldDocValueTypes = new TreeMap<>();

        Map<String, FieldType> fieldTypes = new TreeMap<>();
        Map<String, FieldType[]> collectionFieldTypes = new TreeMap<>();
        Map<String, AbstractType> validators = new TreeMap<>();
        Map<Integer, Pair<String, ByteBuffer>> clusteringKeysIndexed = new LinkedHashMap<>();
        Map<Integer, Pair<String, ByteBuffer>> partitionKeysIndexed = new LinkedHashMap<>();
        Map<String, Analyzer> perFieldAnalyzers;
        Set<String> indexedColumnNames;


        //getForRow all the fields options.
        indexedColumnNames = new TreeSet<>();
        indexedColumnNames.addAll(mapping.getFields().keySet());

        Set<String> added = new HashSet<>(indexedColumnNames.size());
        List<ColumnDefinition> partitionKeys = baseCfs.metadata.partitionKeyColumns();
        List<ColumnDefinition> clusteringKeys = baseCfs.metadata.clusteringKeyColumns();

        for (ColumnDefinition colDef : partitionKeys) {
            String columnName = CFDefinition.definitionType.getString(colDef.name);
            if (Options.logger.isDebugEnabled()) {
                Options.logger.debug("Partition key name is {} and index is {}", colName, colDef.componentIndex);
            }
            validators.put(columnName, colDef.getValidator());
            if (indexedColumnNames.contains(columnName)) {
                int componentIndex = colDef.componentIndex == null ? 0 : colDef.componentIndex;
                partitionKeysIndexed.put(componentIndex, Pair.create(columnName, colDef.name));
                Properties properties = mapping.getFields().get(columnName.toLowerCase());
                addFieldType(columnName, colDef.getValidator(), properties, numericFieldOptions, fieldDocValueTypes, collectionFieldDocValueTypes, fieldTypes, collectionFieldTypes);
                added.add(columnName.toLowerCase());
            }
        }


        for (ColumnDefinition colDef : clusteringKeys) {
            String columnName = CFDefinition.definitionType.getString(colDef.name);
            if (Options.logger.isDebugEnabled()) {
                Options.logger.debug("Clustering key name is {} and index is {}", colName, colDef.componentIndex + 1);
            }
            validators.put(columnName, colDef.getValidator());
            if (indexedColumnNames.contains(columnName)) {
                clusteringKeysIndexed.put(colDef.componentIndex + 1, Pair.create(columnName, colDef.name));
                Properties properties = mapping.getFields().get(columnName.toLowerCase());
                addFieldType(columnName, colDef.getValidator(), properties, numericFieldOptions, fieldDocValueTypes, collectionFieldDocValueTypes, fieldTypes, collectionFieldTypes);
                added.add(columnName.toLowerCase());
            }
        }

        for (String columnName : indexedColumnNames) {
            if (added.add(columnName.toLowerCase())) {
                Properties options = mapping.getFields().get(columnName);
                ColumnDefinition colDef = getColumnDefinition(baseCfs, columnName);
                if (colDef != null) {
                    validators.put(columnName, colDef.getValidator());
                    addFieldType(columnName, colDef.getValidator(), options, numericFieldOptions, fieldDocValueTypes, collectionFieldDocValueTypes, fieldTypes, collectionFieldTypes);
                } else {
                    throw new IllegalArgumentException(String.format("Column Definition for %s not found", columnName));
                }
                if (options.getType() == Properties.Type.object) {
                    mapping.getFields().putAll(options.getFields());
                }
            }

        }
        Set<ColumnDefinition> otherColumns = baseCfs.metadata.regularColumns();
        for (ColumnDefinition colDef : otherColumns) {
            String columnName = CFDefinition.definitionType.getString(colDef.name);
            validators.put(columnName, colDef.getValidator());
        }

        numericFieldOptions.putAll(primary.getDynamicNumericConfig());

        Analyzer defaultAnalyzer = mapping.getLuceneAnalyzer();
        perFieldAnalyzers = mapping.perFieldAnalyzers();
        Analyzer analyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, perFieldAnalyzers);
        Map<String, Properties.Type> types = new TreeMap<>();
        Set<String> nestedFields = new TreeSet<>();
        for (Map.Entry<String, AbstractType> entry : validators.entrySet()) {
            CQL3Type cql3Type = entry.getValue().asCQL3Type();
            AbstractType inner = getValueValidator(cql3Type.getType());
            if (cql3Type.isCollection()) {
                types.put(entry.getKey(), fromAbstractType(inner.asCQL3Type()));
                nestedFields.add(entry.getKey());
            } else {
                types.put(entry.getKey(), fromAbstractType(cql3Type));
            }

        }

        return new Options(primary, numericFieldOptions, fieldDocValueTypes, collectionFieldDocValueTypes, fieldTypes, collectionFieldTypes, types, nestedFields, clusteringKeysIndexed, partitionKeysIndexed, perFieldAnalyzers, indexedColumnNames, analyzer, defaultField);
    }

    private static ColumnDefinition getColumnDefinition(ColumnFamilyStore baseCfs, String columnName) {
        Iterable<ColumnDefinition> cols = baseCfs.metadata.regularAndStaticColumns();
        for (ColumnDefinition columnDefinition : cols) {
            String fromColDef = CFDefinition.definitionType.getString(columnDefinition.name);
            if (fromColDef.equalsIgnoreCase(columnName)) return columnDefinition;
        }
        return null;
    }

    private static void addFieldType(String columnName, AbstractType validator, Properties properties,
                                     Map<String, NumericConfig> numericFieldOptions,
                                     Map<String, FieldType> fieldDocValueTypes, Map<String, FieldType> collectionFieldDocValueTypes,
                                     Map<String, FieldType> fieldTypes, Map<String, FieldType[]> collectionFieldTypes) {

        if (validator.isCollection()) {
            if (validator instanceof MapType) {
                properties.setType(Properties.Type.map);
                MapType mapType = (MapType) validator;
                AbstractType keyValidator = mapType.keys;
                AbstractType valueValidator = mapType.values;
                Properties keyProps = properties.getFields().get("_key");
                Properties valueProps = properties.getFields().get("_value");
                if (keyProps == null) {
                    keyProps = new Properties();
                    keyProps.setAnalyzer(properties.getAnalyzer());
                    properties.getFields().put("_key", keyProps);
                }
                if (valueProps == null) {
                    valueProps = new Properties();
                    valueProps.setAnalyzer(properties.getAnalyzer());
                    properties.getFields().put("_value", valueProps);
                }
                setFromAbstractType(keyProps, keyValidator);
                setFromAbstractType(valueProps, valueValidator);
                FieldType keyFieldType = fieldType(keyProps, keyValidator);
                FieldType valueFieldType = fieldType(valueProps, valueValidator);

                if (valueProps.getStriped() == Properties.Striped.only || valueProps.getStriped() == Properties.Striped.also) {
                    FieldType docValueType = LuceneUtils.docValueTypeFrom(valueFieldType);
                    collectionFieldDocValueTypes.put(columnName, docValueType);
                }
                if (!(valueProps.getStriped() == Properties.Striped.only))
                    collectionFieldTypes.put(columnName, new FieldType[]{keyFieldType, valueFieldType});

            } else if (validator instanceof ListType || validator instanceof SetType) {
                AbstractType elementValidator;
                if (validator instanceof SetType) {
                    SetType setType = (SetType) validator;
                    elementValidator = setType.elements;
                } else {
                    ListType listType = (ListType) validator;
                    elementValidator = listType.elements;
                }
                setFromAbstractType(properties, elementValidator);

                FieldType elementFieldType = fieldType(properties, elementValidator);
                if (properties.getStriped() == Properties.Striped.only || properties.getStriped() == Properties.Striped.also) {
                    FieldType docValueType = LuceneUtils.docValueTypeFrom(elementFieldType);
                    collectionFieldDocValueTypes.put(columnName, docValueType);
                }
                if (!(properties.getStriped() == Properties.Striped.only))
                    collectionFieldTypes.put(columnName, new FieldType[]{elementFieldType});
            }

        } else {
            setFromAbstractType(properties, validator);
            FieldType fieldType = fieldType(properties, validator);
            if (fieldType.numericType() != null) {
                numericFieldOptions.put(columnName, LuceneUtils.numericConfig(fieldType));
            }
            if (properties.getStriped() == Properties.Striped.only || properties.getStriped() == Properties.Striped.also) {
                FieldType docValueType = LuceneUtils.docValueTypeFrom(fieldType);
                fieldDocValueTypes.put(columnName, docValueType);
            }
            if (properties.getStriped() != Properties.Striped.only)
                fieldTypes.put(columnName, fieldType);
        }
    }

    public static void setFromAbstractType(Properties properties, AbstractType type) {
        if (properties.getType() != null) return;
        CQL3Type cqlType = type.asCQL3Type();
        Properties.Type fromAbstractType = fromAbstractType(cqlType);
        properties.setType(fromAbstractType);

    }

    public static Properties.Type fromAbstractType(CQL3Type cqlType) {
        Properties.Type fromAbstractType;
        if (cqlType == CQL3Type.Native.INT) {
            fromAbstractType = Properties.Type.integer;
        } else if (cqlType == CQL3Type.Native.VARINT || cqlType == CQL3Type.Native.BIGINT || cqlType == CQL3Type.Native.COUNTER) {
            fromAbstractType = Properties.Type.bigint;
        } else if (cqlType == CQL3Type.Native.DECIMAL || cqlType == CQL3Type.Native.DOUBLE) {
            fromAbstractType = Properties.Type.bigdecimal;
        } else if (cqlType == CQL3Type.Native.FLOAT) {
            fromAbstractType = Properties.Type.decimal;
        } else if (cqlType == CQL3Type.Native.TEXT || cqlType == CQL3Type.Native.ASCII) {
            fromAbstractType = Properties.Type.text;
        } else if (cqlType == CQL3Type.Native.VARCHAR) {
            fromAbstractType = Properties.Type.string;
        } else if (cqlType == CQL3Type.Native.UUID) {
            fromAbstractType = Properties.Type.string;
        } else if (cqlType == CQL3Type.Native.TIMEUUID) {
            //TimeUUID toString and reorder to make it comparable.
            fromAbstractType = Properties.Type.string;
        } else if (cqlType == CQL3Type.Native.TIMESTAMP) {
            fromAbstractType = Properties.Type.date;
        } else if (cqlType == CQL3Type.Native.BOOLEAN) {
            fromAbstractType = Properties.Type.bool;
        } else {
            fromAbstractType = Properties.Type.text;
        }
        return fromAbstractType;
    }

    public static String getColumnNameStr(CompositeType validator, ByteBuffer colNameBuf) {
        ByteBuffer colName = validator.extractLastComponent(colNameBuf);
        return CFDefinition.definitionType.getString(colName);
    }

    public static String getColumnNameStr(ByteBuffer colName) {
        String s = CFDefinition.definitionType.getString(colName);
        s = StringUtils.removeStart(s, ".").trim();
        return s;
    }

    public static String getColumnName(ColumnDefinition cd) {
        return CFDefinition.definitionType.getString(cd.name);
    }

    public static FieldType fieldType(Properties properties, AbstractType validator) {
        FieldType fieldType = new FieldType();
        fieldType.setIndexed(properties.isIndexed());
        fieldType.setTokenized(properties.isTokenized());
        fieldType.setStored(properties.isStored());
        fieldType.setStoreTermVectors(properties.isStoreTermVectors());
        fieldType.setStoreTermVectorOffsets(properties.isStoreTermVectorOffsets());
        fieldType.setStoreTermVectorPayloads(properties.isStoreTermVectorPayloads());
        fieldType.setStoreTermVectorPositions(properties.isStoreTermVectorPositions());
        fieldType.setOmitNorms(properties.isOmitNorms());
        fieldType.setIndexOptions(properties.getIndexOptions());
        Fields.setNumericType(validator, fieldType);
        if (fieldType.numericType() != null) {
            fieldType.setNumericPrecisionStep(properties.getNumericPrecisionStep());
        }
        return fieldType;
    }

    public static AbstractType getValueValidator(AbstractType abstractType) {
        if (abstractType instanceof CollectionType) {
            if (abstractType instanceof MapType) {
                MapType mapType = (MapType) abstractType;
                return mapType.valueComparator();
            } else if (abstractType instanceof SetType) {
                SetType setType = (SetType) abstractType;
                return setType.nameComparator();
            } else if (abstractType instanceof ListType) {
                ListType listType = (ListType) abstractType;
                return listType.valueComparator();
            }
        }
        return abstractType;
    }

    public static ByteBuffer[] getCompositePKComponents(ColumnFamilyStore baseCfs, ByteBuffer pk) {
        CompositeType baseComparator = (CompositeType) baseCfs.getComparator();
        return baseComparator.split(pk);
    }

    public static org.apache.cassandra.utils.Pair<ByteBuffer, CompositeType.Builder> rowKeyAndBuilder(ColumnFamilyStore table, ByteBuffer primaryKey) {
        ByteBuffer[] components = CassandraUtils.getCompositePKComponents(table, primaryKey);
        final CompositeType baseComparator = (CompositeType) table.getComparator();
        int prefixSize = baseComparator.types.size() - (table.metadata.getCfDef().hasCollections ? 2 : 1);
        CompositeType.Builder builder = baseComparator.builder();
        for (int i = 0; i < prefixSize; i++)
            builder.add(components[i + 1]);
        ByteBuffer rowKey = components[0];
        return org.apache.cassandra.utils.Pair.create(rowKey, builder);
    }

    /**
     * Filter a cached row, which will not be modified by the filter, but may be modified by throwing out
     * tombstones that are no longer relevant.
     * The returned column family won't be thread safe.
     */
    public static ColumnFamily filterColumnFamily(ColumnFamilyStore table, ColumnFamily cached, QueryFilter filter) {
        ColumnFamily cf = cached.cloneMeShallow(ArrayBackedSortedColumns.factory, filter.filter.isReversed());
        OnDiskAtomIterator ci = filter.getColumnFamilyIterator(cached);

        int gcBefore = gcBefore(table, filter.timestamp);
        filter.collateOnDiskAtom(cf, ci, gcBefore);
        return table.removeDeletedCF(cf, gcBefore);
    }

    public static int gcBefore(ColumnFamilyStore table, long now) {
        return (int) (now / 1000) - table.metadata.getGcGraceSeconds();
    }

    public static void load(Map<String, Integer> positions, Tuple tuple, Row row, ColumnFamilyStore table) {
        CompositeType baseComparator = (CompositeType) table.getComparator();
        ColumnFamily cf = row.cf;
        ByteBuffer rowKey = row.key.key;

        Collection<Column> cols = cf.getSortedColumns();
        boolean keyColumnsAdded = false;
        for (Column column : cols) {
            if (!keyColumnsAdded) {
                addKeyColumns(positions, tuple, table, rowKey);
                keyColumnsAdded = true;
            }
            String actualColumnName = CassandraUtils.getColumnNameStr(baseComparator, column.name());
            ByteBuffer colValue = column.value();
            AbstractType<?> valueValidator = table.metadata.getValueValidatorFromColumnName(column.name());
            if (valueValidator.isCollection()) {
                CollectionType validator = (CollectionType) valueValidator;
                AbstractType keyType = validator.nameComparator();
                AbstractType valueType = validator.valueComparator();
                ByteBuffer[] components = baseComparator.split(column.name());
                ByteBuffer keyBuf = components[components.length - 1];
                if (valueValidator instanceof MapType) {
                    actualColumnName = actualColumnName + "." + keyType.compose(keyBuf);
                    valueValidator = valueType;
                } else if (valueValidator instanceof SetType) {
                    colValue = keyBuf;
                    valueValidator = keyType;
                } else {
                    valueValidator = valueType;
                }
            }
            for (String field : positions.keySet()) {
                if (actualColumnName.equalsIgnoreCase(field)) {
                    tuple.getTuple()[positions.get(field)] = valueValidator.compose(colValue);
                }
            }
        }
    }

    private static void addKeyColumns(Map<String, Integer> positions, Tuple tuple, ColumnFamilyStore table, ByteBuffer rowKey) {
        CFDefinition cfDef = table.metadata.getCfDef();
        AbstractType<?> keyValidator = table.metadata.getKeyValidator();
        ByteBuffer[] keyComponents = cfDef.hasCompositeKey ? ((CompositeType) table.metadata.getKeyValidator()).split(rowKey) : new ByteBuffer[]{rowKey};
        List<AbstractType<?>> keyValidators = keyValidator.getComponents();
        List<ColumnDefinition> partitionKeys = table.metadata.partitionKeyColumns();

        for (ColumnDefinition entry : partitionKeys) {
            int componentIndex = entry.componentIndex == null ? 0 : entry.componentIndex;
            ByteBuffer value = keyComponents[componentIndex];
            AbstractType<?> validator = keyValidators.get(componentIndex);
            String actualColumnName = CFDefinition.definitionType.getString(entry.name);
            for (String field : positions.keySet()) {
                if (actualColumnName.equalsIgnoreCase(field)) {
                    tuple.getTuple()[positions.get(field)] = validator.compose(value);
                }
            }
        }
    }


}