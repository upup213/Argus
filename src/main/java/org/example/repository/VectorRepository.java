package org.example.repository;

import com.google.gson.Gson;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.example.constant.SecurityConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 向量数据访问层 — 封装所有 Milvus gRPC 参数构建。
 * Service 类不得直接构建 io.milvus.param.* Builder，必须通过此类委托。
 */
@Repository
public class VectorRepository {

    private static final Logger logger = LoggerFactory.getLogger(VectorRepository.class);

    /** Gson 单例（线程安全，全局复用）*/
    public static final Gson GSON = new Gson();

    @Autowired
    private MilvusServiceClient client;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MilvusProperties milvusProperties;

    // ------------------------------------------------------------------
    // Collection lifecycle
    // ------------------------------------------------------------------

    /**
     * 加载集合（容忍 status=0 或 status=65535「已加载」）。
     */
    public void loadCollectionOnce(String collectionName) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Counter c = meterRegistry.counter("sba.milvus.ops", "op", "load");
        try {
            R<RpcStatus> resp = client.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()
            );
            if (resp.getStatus() != 0 && resp.getStatus() != MilvusConstants.MILVUS_LOADED_STATUS) {
                logger.warn("加载 collection 失败: {}", resp.getMessage());
            }
        } finally {
            sample.stop(Timer.builder("sba.milvus.ops.latency")
                    .tag("op", "load")
                    .register(meterRegistry));
            c.increment();
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * 按来源路径删除已有数据（source scalar field + backward-compat metadata["_source"] dual-filter）。
     *
     * @param filePath      原始文件路径
     * @param collectionName 集合名
     * @return 实际删除的记录数
     */
    public long deleteBySource(String filePath, String collectionName) {
        try {
            // 文件名白名单校验（防御纵深）
            int lastSep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            String fileName = filePath.substring(lastSep + 1);
            if (!fileName.matches(SecurityConstants.SAFE_FILENAME_REGEX)) {
                logger.warn("删除旧数据被拒绝：非法文件名: {}",
                        fileName.replaceAll("\\p{Cntrl}", "?"));
                return 0L;
            }

            // 路径标准化
            String normalizedPath = Paths.get(filePath).normalize().toString().replace(File.separator, "/");
            // Use source scalar field as primary key; keep _source for backward compat
            String expr = String.format("source == \"%s\"", escapeExprString(normalizedPath));

            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载
            loadCollectionOnce(collectionName);

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();

            Timer.Sample sample = Timer.start(meterRegistry);
            Counter c = meterRegistry.counter("sba.milvus.ops", "op", "delete");
            try {
                R<MutationResult> response = client.delete(deleteParam);
                if (response.getStatus() != 0) {
                    logger.warn("删除旧数据时出现警告: {}", response.getMessage());
                    return 0L;
                }
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
                return deletedCount;
            } finally {
                sample.stop(Timer.builder("sba.milvus.ops.latency")
                        .tag("op", "delete")
                        .register(meterRegistry));
                c.increment();
            }
        } catch (Exception e) {
            logger.warn("删除旧数据失败: {}", e.getMessage());
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /**
     * 相似搜索 — COSINE distance, nprobe injected from config.
     */
    public R<SearchResults> search(String collectionName, List<Float> queryVector,
                                    int topK, List<String> outFields, String nprobeJson) {
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("vector")
                .withVectors(List.of(queryVector))
                .withTopK(topK)
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withOutFields(outFields)
                .withParams(nprobeJson)
                .build();

        Timer.Sample sample = Timer.start(meterRegistry);
        Counter c = meterRegistry.counter("sba.milvus.ops", "op", "search");
        try {
            return client.search(searchParam);
        } finally {
            sample.stop(Timer.builder("sba.milvus.ops.latency")
                    .tag("op", "search")
                    .register(meterRegistry));
            c.increment();
        }
    }

    // ------------------------------------------------------------------
    // Insert (T2 — true columnar batch via InsertParam.Field with multi-value lists)
    // ------------------------------------------------------------------

    /**
     * 批量插入字段值到指定集合（列式格式）。
     * 每个 Field 的值列表长度即为本次插入的记录数，所有字段的值列表必须等长。
     *
     * @param collectionName 集合名
     * @param fields         字段列表（每 field 是一个列：List&lt;String&gt; / List&lt;List&lt;Float&gt;&gt; / List&lt;JsonObject&gt;）
     * @return 实际插入的记录数
     */
    public long insertBatch(String collectionName, List<InsertParam.Field> fields) {
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        Timer.Sample sample = Timer.start(meterRegistry);
        Counter c = meterRegistry.counter("sba.milvus.ops", "op", "insert");
        try {
            R<MutationResult> response = client.insert(insertParam);
            if (response.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + response.getMessage());
            }
            // Use MutationResultWrapper to access insertion results (SDK 2.x API)
            io.milvus.response.MutationResultWrapper wrapper = new io.milvus.response.MutationResultWrapper(response.getData());
            long count = wrapper.getInsertCount();
            logger.debug("向量插入成功: {} 条", count);
            return count;
        } finally {
            sample.stop(Timer.builder("sba.milvus.ops.latency")
                    .tag("op", "insert")
                    .register(meterRegistry));
            c.increment();
        }
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    /**
     * Milvus 过滤表达式字符串字面量转义。
     * 先 \ -> \\，再 " -> \"，顺序不可颠倒。
     */
    public static String escapeExprString(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 根据 _source + chunkIndex 生成唯一 ID。
     */
    public static String generateId(String source, int chunkIndex) {
        return UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();
    }
}
