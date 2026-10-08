package org.example.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.ShowCollectionsParam;
import org.example.common.ApiResponse;
import org.example.common.BizException;
import org.example.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Milvus 测试控制器
 * 用于测试数据库连接和数据读取
 */
@RestController
@RequestMapping("/milvus")
public class MilvusCheckController {

    private static final Logger logger = LoggerFactory.getLogger(MilvusCheckController.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    /**
     * 简单的健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> simpleHealth() {
        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                ShowCollectionsParam.newBuilder().build()
            );

            if (response.getStatus() == 0) {
                Map<String, Object> data = new HashMap<>();
                data.put("collections", response.getData().getCollectionNamesList());
                return ResponseEntity.ok(ApiResponse.success(data));
            } else {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, response.getMessage());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Milvus 健康检查失败", e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "Milvus 连接失败");
        }
    }
}
