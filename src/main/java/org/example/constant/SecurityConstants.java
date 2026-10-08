package org.example.constant;

import org.example.util.FilenameSanitizer;

/**
 * 安全相关公共常量。
 * 供文件名净化（上传落盘）、Milvus 过滤表达式来源白名单等场景复用，属于公共契约。
 */
public class SecurityConstants {

    /**
     * 安全文件名白名单正则，统一收敛在 {@link FilenameSanitizer#SAFE_FILENAME_REGEX}。
     * 此处保留别名以兼容既有引用（FileUploadController / VectorIndexService）。
     */
    public static final String SAFE_FILENAME_REGEX = FilenameSanitizer.SAFE_FILENAME_REGEX;

    private SecurityConstants() {
        // 工具类，禁止实例化
    }
}
