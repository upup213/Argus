package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 最小可用鉴权过滤器（X-API-Key）
 * <p>
 * 不引入 Spring Security，纯 Servlet 过滤器实现：
 * <ul>
 *   <li>静态资源（/、/index.html、/app.js、/styles.css、/vendor/**、/error）免鉴权；</li>
 *   <li>其余接口（/api/**、/milvus/**，含 SSE 端点 /api/chat_stream、/api/ai_ops）必须携带正确 X-API-Key 头；</li>
 *   <li>Key 比较使用 {@link MessageDigest#isEqual} 恒定时间比较，防时序侧信道；</li>
 *   <li>鉴权失败返回 HTTP 401 + 统一错误响应体（固定常量 JSON，不插值任何外部输入）。</li>
 * </ul>
 * 明确取舍：仅防匿名滥用/盗刷 LLM 费用/误删数据，不提供用户身份、RBAC、审计、防重放；
 * 完整 Spring Security 留待后续阶段，届时 X-API-Key Header 与 401 错误体格式不变。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /**
     * 401 统一错误响应体（固定常量，与 spec 统一错误响应体一致）
     */
    private static final String UNAUTHORIZED_BODY = "{\"code\":401,\"message\":\"unauthorized\",\"data\":null}";

    @Value("${security.api-key}")
    private String apiKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        // OPTIONS 预检请求放行：浏览器预检不携带 X-API-Key 自定义头，由 CORS 层决定来源是否允许
        return "OPTIONS".equalsIgnoreCase(req.getMethod())
                || p.equals("/") || p.equals("/index.html") || p.equals("/app.js")
                || p.equals("/styles.css") || p.startsWith("/vendor/") || p.equals("/error")
                || p.startsWith("/actuator/health") || p.startsWith("/actuator/info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String provided = req.getHeader("X-API-Key");
        if (provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(UNAUTHORIZED_BODY);
        }
    }
}
