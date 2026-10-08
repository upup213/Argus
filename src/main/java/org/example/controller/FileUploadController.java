package org.example.controller;

import org.example.common.ApiResponse;
import org.example.common.BizException;
import org.example.common.ErrorCode;
import org.example.config.FileUploadConfig;
import org.example.constant.SecurityConstants;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.example.task.IndexTaskManager;
import org.example.util.FilenameSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private IndexTaskManager indexTaskManager;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }

        // ① 剥离目录成分：统一分隔符后仅取纯文件名，覆盖 ../..、绝对路径、Windows 盘符等穿越手法
        String safeName = Paths.get(originalFilename.replace("\\", "/")).getFileName().toString();

        // ② 文件名白名单：拒绝 "."、".." 及不在白名单正则内的文件名（含引号、空格、控制字符等）
        if (safeName.equals(".") || safeName.equals("..")
                || !safeName.matches(SecurityConstants.SAFE_FILENAME_REGEX)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名不合法");
        }

        String fileExtension = FilenameSanitizer.getFileExtension(safeName);
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        List<String> allowedList = (allowedExtensions == null || allowedExtensions.isEmpty())
                ? Collections.emptyList()
                : Arrays.asList(allowedExtensions.split(","));
        if (!FilenameSanitizer.isAllowedExtension(fileExtension, allowedList)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 使用净化后的文件名，而不是UUID，以便实现基于文件名的去重
            Path filePath = uploadDir.resolve(safeName).normalize().toAbsolutePath();

            // ③ 权威兜底：归一化后必须仍在上传目录内，否则按非法路径拒绝
            if (!filePath.startsWith(uploadDir.toAbsolutePath().normalize())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "非法文件路径");
            }

            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }
            
            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            // 异步提交索引任务，立即返回 taskId；通过 taskId 查询索引状态
            String taskId = indexTaskManager.submitFile(filePath.toString());
            logger.info("索引任务已提交: {} for file {}", taskId, filePath);

            FileUploadRes response = new FileUploadRes(
                    safeName,
                    filePath.toString(),
                    file.getSize()
            );
            response.setTaskId(taskId);
            response.setIndexed(false);

            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (IOException e) {
            logger.error("文件上传失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "上传失败");
        }
    }

}
