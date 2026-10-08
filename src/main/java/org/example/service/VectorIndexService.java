package org.example.service;

import com.google.gson.JsonObject;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.config.MilvusProperties;
import org.example.dto.DocumentChunk;
import org.example.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    // Default batch sizes (overridden by application.yml via @Value)
    private static final int DEFAULT_EMBEDDING_BATCH_SIZE = 10;
    private static final int DEFAULT_INSERT_BATCH_SIZE = 256;

    @Autowired
    private VectorRepository vectorRepository;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private MilvusProperties milvusProperties;

    @Value("${file.upload.path}")
    private String uploadPath;

    /** DashScope embedding API 批量大小（每次请求的文本数） */
    @Value("${dashscope.embedding.batch-size:10}")
    private Integer embeddingBatchSizeOverride;

    /** Milvus 批量插入大小（每批的记录数） */
    @Value("${index.insert.batch-size:256}")
    private Integer insertBatchSizeOverride;

    /** 索引文件最大体积（HumanReadable，如 "10MB"） */
    @Value("${index.max-file-size:10MB}")
    private DataSize indexMaxFileSize;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件（T2 批量流程：批量向量化 + 单次 loadCollection + 批量插入）。
     *
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();

        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 检查文件大小（防止超大文件 OOM）
        long fileSize = Files.size(path);
        long maxSize = indexMaxFileSize != null ? indexMaxFileSize.toBytes() : 10 * 1024 * 1024;
        if (fileSize > maxSize) {
            throw new RuntimeException(
                    "文件超过大小限制: " + path.getFileName() + " (" + fileSize + "/" + maxSize + " bytes)");
        }

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片（章节->段落(包含重叠)）
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        if (chunks.isEmpty()) {
            logger.warn("文档分片为空，跳过索引: {}", filePath);
            return;
        }

        // 4. BATCH EMBEDDING: partition texts, call generateEmbeddings per batch
        List<String> texts = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            texts.add(chunk.getContent());
        }

        int batchSize = embeddingBatchSizeOverride != null ? embeddingBatchSizeOverride : DEFAULT_EMBEDDING_BATCH_SIZE;
        List<List<Float>> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            allVectors.addAll(embeddingService.generateEmbeddings(batch));
        }

        // Validate embedding count matches chunk count
        if (allVectors.size() != chunks.size()) {
            throw new RuntimeException("批量向量化结果条数与分片数不匹配: 期望 " + chunks.size() + ", 实际 " + allVectors.size());
        }

        // 5. Load collection ONCE per file (from config)
        vectorRepository.loadCollectionOnce(milvusProperties.getCollectionName());

        // 6. BATCH INSERT: partition rows, call insertBatch per batch
        final int totalChunks = chunks.size();
        int insertBatchSz = insertBatchSizeOverride != null ? insertBatchSizeOverride : DEFAULT_INSERT_BATCH_SIZE;
        for (int i = 0; i < chunks.size(); i += insertBatchSz) {
            int end = Math.min(i + insertBatchSz, chunks.size());

            List<String> ids = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<List<Float>> vectors = new ArrayList<>();
            List<Object> metadataList = new ArrayList<>();
            List<String> sources = new ArrayList<>();

            for (int j = i; j < end; j++) {
                DocumentChunk chunk = chunks.get(j);
                List<Float> vector = allVectors.get(j);

                String normalizedPath = buildNormalizedPath(path.toString());
                String id = UUID.nameUUIDFromBytes((normalizedPath + "_" + j).getBytes()).toString();
                ids.add(id);
                contents.add(chunk.getContent());
                vectors.add(vector);

                // Dual-write: set BOTH source column AND metadata["_source"] for backward compat
                JsonObject metaJson = VectorRepository.GSON.toJsonTree(
                        buildMetadataForChunk(chunk, path, totalChunks)).getAsJsonObject();
                metadataList.add(metaJson);
                sources.add(normalizedPath);
            }

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", ids));
            fields.add(new InsertParam.Field("content", contents));
            fields.add(new InsertParam.Field("vector", vectors));
            fields.add(new InsertParam.Field("metadata", metadataList));
            fields.add(new InsertParam.Field("source", sources));

            vectorRepository.insertBatch(milvusProperties.getCollectionName(), fields);
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    /**
     * 规范化文件路径：统一使用正斜杠作为分隔符。
     */
    private String buildNormalizedPath(String filePath) {
        return Paths.get(filePath).normalize().toString().replace(File.separator, "/");
    }

    /**
     * 删除文件的旧数据（根据 source scalar field）。
     * 委托给 VectorRepository 处理所有 Milvus gRPC 参数构建。
     */
    private void deleteExistingData(String filePath) {
        vectorRepository.deleteBySource(filePath, milvusProperties.getCollectionName());
    }

    /**
     * 构建单条分片的元数据（包含文件信息）。
     * 保持与原有行为一致的元数据键：_source, _extension, _file_name, chunkIndex, totalChunks, title。
     */
    private Map<String, Object> buildMetadataForChunk(DocumentChunk chunk, Path path, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();

        String normalizedPath = buildNormalizedPath(path.toString());
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex);
        }

        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileName);

        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }

        return metadata;
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
