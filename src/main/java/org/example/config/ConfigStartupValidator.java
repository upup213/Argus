package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动期敏感配置校验（fail-fast）
 * <p>
 * 校验 {@code dashscope.api.key} 与 {@code security.api-key} 两个敏感配置：
 * 任一为 null、空串或纯空白（例如环境变量被显式置为空串）时抛出 {@link IllegalStateException}，
 * 使应用在启动阶段直接失败，而不是运行到首次调用才暴露问题。
 * 报错信息只包含缺失的键名，不打印任何配置值。
 * </p>
 */
@Component
public class ConfigStartupValidator {

    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    @Value("${security.api-key}")
    private String appApiKey;

    @PostConstruct
    public void validate() {
        // 校验失败只报键名，不输出任何密钥内容
        if (dashscopeApiKey == null || dashscopeApiKey.trim().isEmpty()) {
            throw new IllegalStateException("dashscope.api.key 缺失或为空");
        }
        if (appApiKey == null || appApiKey.trim().isEmpty()) {
            throw new IllegalStateException("security.api-key 缺失或为空");
        }
    }
}
