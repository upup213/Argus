package org.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 使用 Spring Boot 自动配置的 ObjectMapper 和 HttpMessageConverter（已支持 UTF-8 JSON）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // 无需自定义配置，Spring Boot 自动配置的转换器已包含 UTF-8 JSON 支持
}
