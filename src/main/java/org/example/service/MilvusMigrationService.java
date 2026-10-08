package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import org.example.client.MilvusClientFactory;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Milvus collection migration service.
 * Handles the four-step migration workflow: create a new collection with COSINE metric
 * and source scalar field alongside the existing L2 + metadata-only collection.
 */
@Component
public class MilvusMigrationService {

    private static final String SOURCE_FIELD_NAME = "source";

    private final Logger logger = LoggerFactory.getLogger(MilvusMigrationService.class);

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    @Autowired
    private MilvusProperties properties;

    /**
     * Check whether the target (new) collection is currently active — i.e. the config
     * points to the new collection name (default "biz").  The caller should set
     * {@code milvus.collection-name} to e.g. "biz_v2" to flip the switch.
     */
    public boolean isNewCollectionActive() {
        return properties.getCollectionName().equalsIgnoreCase("biz");
    }

    /**
     * On startup: if the configured target collection does not exist, create it
     * with the updated schema (COSINE + source scalar field) and indexes.
     */
    public void ensureCollectionExists() {
        String collectionName = properties.getCollectionName();
        MilvusServiceClient client = milvusClientFactory.createClient();
        try {
            if (!client.collectionExists(collectionName)) {
                createNewCollection(client, collectionName);
                logger.info("Created new collection: {}", collectionName);
            } else {
                logger.info("Collection '{}' already exists, skipping creation", collectionName);
            }
        } finally {
            client.close();
        }
    }

    /**
     * Create a brand-new collection with:
     * - Schema: id (VarChar PK), vector (FloatVector, dim=1024), content (VarChar),
     *   metadata (JSON), source (VarChar, maxLength=1024)
     * - Index: IVF_FLAT + COSINE + nlist=128 on vector field
     * - Scalar index: INVERTED on source field
     */
    private void createNewCollection(MilvusServiceClient client, String collectionName) {
        // --- Define fields ---
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        FieldType sourceField = FieldType.newBuilder()
                .withName(SOURCE_FIELD_NAME)
                .withDataType(DataType.VarChar)
                .withMaxLength(1024)
                .build();

        // --- Build schema ---
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .addFieldType(sourceField)
                .build();

        // --- Create collection ---
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("Business knowledge collection (COSINE + source field)")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> createResp = client.createCollection(createParam);
        if (createResp.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + createResp.getMessage());
        }

        // --- Create vector index (IVF_FLAT + COSINE) ---
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> vectorIndexResp = client.createIndex(vectorIndexParam);
        if (vectorIndexResp.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + vectorIndexResp.getMessage());
        }
        logger.info("Created IVF_FLAT+COSINE index on vector field for collection '{}'", collectionName);

        // --- Create scalar index on source field (INVERTED) ---
        CreateIndexParam sourceIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(SOURCE_FIELD_NAME)
                .withIndexType(IndexType.INVERTED)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> sourceIndexResp = client.createIndex(sourceIndexParam);
        if (sourceIndexResp.getStatus() != 0) {
            throw new RuntimeException("创建 source 字段索引失败: " + sourceIndexResp.getMessage());
        }
        logger.info("Created INVERTED index on source field for collection '{}'", collectionName);
    }

    /** Return the fixed name used internally for the source column. */
    public String getSourceFieldName() {
        return SOURCE_FIELD_NAME;
    }
}
