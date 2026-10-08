package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    private final VectorSearchService vectorSearchService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        

        try {
            // 使用向量搜索服务检索相关文档
            List<VectorSearchService.SearchResult> searchResults = 
                    vectorSearchService.searchSimilarDocuments(query, topK);
            
            if (searchResults.isEmpty()) {
                // 无结果分支：Jackson 序列化 Map，禁止裸 JSON 字面量/字符串拼接
                return objectMapper.writeValueAsString(
                        Map.of("status", "no_results",
                                "message", "No relevant documents found in the knowledge base."));
            }
            
            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(searchResults);
            

            return resultJson;
            
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            // 异常分支：Jackson 序列化 Map，e.getMessage() 经序列化天然转义，杜绝引号/换行破坏 JSON
            try {
                return objectMapper.writeValueAsString(
                        Map.of("status", "error", "message", "Failed to query internal docs: " + e.getMessage()));
            } catch (Exception ex) {
                // 序列化兜底：固定常量 JSON，不做任何插值，不允许异常抛出方法外
                logger.error("错误响应序列化失败", ex);
                return "{\"status\":\"error\",\"message\":\"error response serialization failed\"}";
            }
        }
    }
}
