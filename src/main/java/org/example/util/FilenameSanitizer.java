package org.example.util;

import java.util.Collection;

/**
 * 文件名净化工具
 * 集中处理文件名扩展名解析、扩展名白名单校验、安全文件名白名单正则等与文件名相关的安全逻辑。
 */
public final class FilenameSanitizer {

    /**
     * 安全文件名白名单正则：仅允许字母、数字、中文（U+4E00 至 U+9FA5 区间）、点、下划线、连字符，长度 1~128。
     * 注意：Java 字符串字面量中写作双反斜杠形式，保证运行时正则引擎收到的是 Unicode 转义序列。
     */
    public static final String SAFE_FILENAME_REGEX = "[A-Za-z0-9\\u4e00-\\u9fa5._-]{1,128}";

    private FilenameSanitizer() {
        // 工具类，禁止实例化
    }

    /**
     * 获取文件名扩展名（小写归一，无后缀返回空串）。
     *
     * @param filename 文件名
     * @return 小写扩展名；无后缀时返回空字符串
     */
    public static String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    /**
     * 判断扩展名是否在白名单内（大小写不敏感）。
     *
     * @param extension         扩展名（可为空）
     * @param allowedExtensions 允许的扩展名集合
     * @return 命中白名单返回 true
     */
    public static boolean isAllowedExtension(String extension, Collection<String> allowedExtensions) {
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        return allowedExtensions.contains(extension.toLowerCase());
    }
}
