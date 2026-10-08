# P0 阶段方案：安全止血

> 适用工程：`d:\代码\SuperBizAgent-release-2026-05-17`（Maven 单模块，Java 17 + Spring Boot 3.2.0 + Spring AI Alibaba 1.1.0.0-RC2 + DashScope + Milvus 2.6.x，入口 `org.example.Main`，前端为 `src/main/resources/static` 下原生 HTML/JS）。
> 文中所有路径、行号、方法名均基于当前代码快照核实；行号在后续修改后会漂移，以"文件 + 方法/代码特征"为准。

## 1. 背景与目标

### 1.1 背景
项目是面向运维 OnCall 的 RAG 知识库问答 + Agent 工具调用（查日志 `QueryLogsTools` / 查告警 `QueryMetricsTools` / 查文档 `InternalDocsTools`）服务，支持 SSE 流式输出。经逐项核查源码，当前存在 8 类上线前安全硬阻塞问题：

| # | 问题 | 核实位置（当前快照） |
|---|------|----------------------|
| 1 | 真实 DashScope API Key 明文写入配置默认值，疑似已泄露 | `src/main/resources/application.yml` 第 26、55 行；第 50 行 MCP SSE 端点含凭证路径 |
| 2 | 文件上传路径穿越 | `src/main/java/org/example/controller/FileUploadController.java` 第 59 行 `uploadDir.resolve(originalFilename).normalize()`，normalize 后未校验是否仍在 uploadDir 内 |
| 3 | Milvus 过滤表达式注入（delete 误删数据） | `src/main/java/org/example/service/VectorIndexService.java` 第 181 行 `deleteExistingData()` 中 `String.format("metadata[\"_source\"] == \"%s\"", normalizedPath)` 直接拼接 |
| 4 | 日志泄露密钥/向量 | `src/main/java/org/example/service/VectorEmbeddingService.java` 第 42 行（打印完整 API Key）、61 行（打印 Key 前 8 位）、91-92 行（debug 打印 Key 前 8 位）、106 行（打印完整向量） |
| 5 | 前端 XSS | `src/main/resources/static/app.js` `renderMarkdown()`（第 62-78 行）`marked.parse` 结果直接返回，调用点（第 749、762、772、788、842、1347、1421 行）赋给 `innerHTML` |
| 6 | 全接口匿名可用 + CORS 全开 | `/api/upload`、`/api/chat`、`/api/chat_stream`、`/api/ai_ops`（消耗 LLM 费用）、`/api/chat/clear`、`/api/chat/session/{sessionId}`、`/milvus/health` 均无鉴权；`src/main/java/org/example/config/WebMvcConfig.java` 第 17-21 行 `allowedOrigins("*")` |
| 7 | 前端 CDN 外网依赖，企业内网不可用 | `src/main/resources/static/index.html` 第 9-12 行引用 jsdelivr 的 marked@11.1.1、highlight.js@11.9.0 |
| 8 | 手拼 JSON 未转义，产生非法 JSON | `QueryLogsTools.buildErrorResponse()` 第 599 行、`QueryMetricsTools.buildErrorResponse()` 第 231 行、`InternalDocsTools.queryInternalDocs()` 第 75-76 行，均用 `String.format` 拼 JSON，消息含引号/换行时输出非法 JSON，污染 LLM 工具调用结果 |

### 1.2 目标
1. 消除上述 8 项上线前硬阻塞（安全止血），不引入功能性改动。
2. 建立可被 P1/P2/P3 沿用的"对外约定"（敏感配置命名、鉴权 Header、统一错误响应体、文件名白名单、表达式转义等），避免后续阶段返工。
3. 技术栈保持一致（Java 17、Spring Boot 3.2.x、Maven、Jackson、SLF4J），除本地化的前端静态库（DOMPurify）外不新增后端依赖。

## 2. 范围界定

### 2.1 本期做什么
1. **T1 密钥管理**：配置外置（环境变量、无默认值）、启动校验失败快速退出、密钥轮换操作指引、敏感配置不入库。
2. **T2 文件上传路径穿越修复**：只取文件名 + 白名单 + `startsWith` 越界校验。
3. **T3 Milvus 过滤表达式注入修复**：字面量转义 + 文件名白名单双层防护。
4. **T4 日志泄露清理**：清除密钥/完整向量打印。
5. **T5 前端 XSS 修复**：引入 DOMPurify（本地化）清洗 Markdown 渲染结果。
6. **T6 最小可用鉴权 + CORS 收紧**：`X-API-Key` 过滤器 + CORS 白名单（说明与完整 Spring Security 的取舍）。
7. **T7 前端 CDN 依赖本地化**：marked / highlight.js / DOMPurify 全部本地化，保留 SRI/哈希校验能力。
8. **T8 手拼 JSON 修复**：三处 `String.format` 拼 JSON 统一改为 Jackson 序列化。

### 2.2 明确不做什么（留待后续阶段）
- 不引入完整 Spring Security、用户体系、登录、RBAC、审计日志（P3 产品化阶段再做用户级鉴权）。
- 不引入 Vault/KMS/配置中心（本期用环境变量 + 启动校验；密钥托管演进见 P2/P3）。
- 不做速率限制/防刷、HTTPS 证书、反向代理/网关层鉴权（基础设施层职责）。
- 不做依赖漏洞扫描流程、CI、单元测试体系（P1）。
- 不做批量索引/异步索引、Redis 会话改造（P2）；但 T3 的转义工具、T1 的配置命名需被 P2 沿用。
- 不做 Milvus 连接的用户名/密码加固、TLS（`application.yml` 第 17-18 行 `milvus.username/password` 为空，属运维配置项，本期仅在第 5 节给出约定）。
- 不修改任何业务功能与提示词（`RagService.buildPrompt()` 等不动）。

### 2.3 建议实施顺序（依赖驱动）
`T1（密钥轮换必须最先） → T2 → T3 → T4 → T8 → T7 → T5 → T6（T5/T7/T6 都动前端，放一起收尾，T6 依赖统一错误体与前端改造点）`

## 3. 任务清单

> 优先级定义：**P0-致命**（密钥已泄露，立即执行）｜**P0-高**（可被远程利用导致文件篡改/数据误删/接口盗刷）｜**P0-中**（信息泄露/前端安全/可用性阻塞）。

---

### T1｜P0-致命｜密钥外置 + 轮换 + 启动校验，敏感配置不入库

**涉及文件**
- `src/main/resources/application.yml`（第 26 行 `spring.ai.dashscope.api-key`、第 50 行 `spring.ai.mcp.client.sse.connections.tencent-cls.sse-endpoint`、第 55 行 `dashscope.api.key`）
- `src/main/java/org/example/service/VectorEmbeddingService.java`（`init()` 第 38-67 行已有部分校验，需配合收紧）
- 新增（允许）：`src/main/resources/application.yml.example`（无真实值的样例，随仓库分发）

**具体改动**
1. `application.yml` 中**去掉默认值**，只保留占位符（无默认值时 Spring 在启动期解析不到 `${DASHSCOPE_API_KEY}` 会直接抛 `IllegalArgumentException` 使应用启动失败，天然满足"启动校验失败快速退出"）：
   ```yaml
   spring:
     ai:
       dashscope:
         api-key: ${DASHSCOPE_API_KEY}   # 删除第 26 行的真实 Key 与"或使用默认值"注释
       mcp:
         client:
           sse:
             connections:
               tencent-cls:
                 url: https://mcp-api.tencent-cloud.com
                 sse-endpoint: ${MCP_TENCENT_CLS_SSE_ENDPOINT}   # 第 50 行原为 /sse/92XXXXXXXXb4（路径即凭证）
   dashscope:
     api:
       key: ${DASHSCOPE_API_KEY}         # 第 55 行同步处理
   ```
   另新增 `security.api-key: ${APP_API_KEY}`（供 T6 使用，命名见第 5 节）。
2. 显式启动校验（兜底拦截"空串/占位符残留"这类 `${VAR:}` 带空默认值或运维误配场景）：在 `VectorEmbeddingService.init()` 现有校验（第 41-44 行）基础上，改为不打印值、只报缺失的键名，并保留 `throw new IllegalStateException(...)` 快速退出；同时覆盖 `dashscope.api.key` 与 `security.api-key` 的非空校验（可抽一个 `ApplicationRunner` 统一校验，也可沿用 `@PostConstruct`，二选一，保持简单）。
3. 敏感配置不入库：
   - 该工程当前**未初始化 Git 仓库**（无 `.git`，已核实）；一旦纳入版本管理，`application.yml` 不得含明文密钥。`.gitignore` 追加本地覆盖文件 `application-local.yml`、`.env`。
   - 提供 `application.yml.example`（键名齐全、值为空占位），真实值只存在于部署环境的环境变量/启动脚本中。
4. **密钥轮换操作指引**（必须按序执行，旧 Key 视为已泄露）：
   1. 阿里云百炼/DashScope 控制台 → API-KEY 管理 → **创建新 Key**（不要先禁用旧 Key）；
   2. 在部署环境更新环境变量 `DASHSCOPE_API_KEY` 为新 Key，重启服务（`VectorEmbeddingService` 与 `spring.ai.dashscope` 两处消费同一变量，重启后均生效）；
   3. 冒烟验证：`/api/upload` 上传一个 txt 触发 embedding、`/api/chat` 发起一次对话，确认无鉴权报错；
   4. 控制台**禁用/删除旧 Key**，观察旧 Key 调用告警；
   5. MCP SSE 端点凭证（`/sse/92XXXXXXXXb4` 形态的路径 token）在腾讯云 MCP 服务侧重新生成，更新 `MCP_TENCENT_CLS_SSE_ENDPOINT`；
   6. 全盘检索历史交付物/文档/聊天记录中是否出现过该 Key（`grep -r "sk-ws-"`），凡出现处一律按已泄露处理。

**验收标准**
- `src/main/resources/application.yml` 及其任何历史副本中检索不到 `sk-` 明文与完整 MCP 端点 token；
- 未设置 `DASHSCOPE_API_KEY` 启动 → 进程启动失败并明确报出缺失键名（而非运行到第一次调用才失败）；
- 设置环境变量后服务正常启动，embedding/对话功能与改造前一致；
- 新 Key 生效、旧 Key 已在控制台禁用；`application.yml.example` 存在且无真实值。

---

### T2｜P0-高｜文件上传路径穿越修复

**涉及文件**
- `src/main/java/org/example/controller/FileUploadController.java`（`upload()` 第 35-103 行，问题点第 59 行）

**具体改动**
在第 59 行 `uploadDir.resolve(originalFilename).normalize()` 前后加三层防护（保持现有"以文件名去重、覆盖更新"的语义不变，见第 58 行注释）：

```java
// 1) 只取文件名，剥离任何目录成分（../..、绝对路径、Windows 盘符等）
String safeName = Paths.get(originalFilename.replace("\\", "/")).getFileName().toString();

// 2) 文件名白名单：中文/字母/数字/._-，长度 1~128；拒绝 "." 与 ".."
if (safeName.equals(".") || safeName.equals("..")
        || !safeName.matches("[A-Za-z0-9\\u4e00-\\u9fa5._-]{1,128}")) {
    return ResponseEntity.badRequest().body("文件名不合法");
}

// 3) 归一化后必须仍位于 uploadDir 内（权威兜底，覆盖平台差异）
Path filePath = uploadDir.resolve(safeName).normalize().toAbsolutePath();
if (!filePath.startsWith(uploadDir.toAbsolutePath().normalize())) {
    return ResponseEntity.badRequest().body("非法文件路径");
}
```

配套：
- 扩展名校验（第 45-49 行 `getFileExtension`/`isAllowedExtension`，白名单来自 `FileUploadConfig.getAllowedExtensions()` 即 `file.upload.allowed-extensions: txt,md`）改用 `safeName` 计算；
- 后续 `Files.copy`、`FileUploadRes`（第 82-86 行）、`vectorIndexService.indexSingleFile()`（第 74 行）一律使用 `safeName`/`filePath`，不再回用原始 `originalFilename`；
- 白名单正则提取为常量 `SAFE_FILENAME_REGEX`，供 T3 与 P2 复用（见第 5 节约定）。

**验收标准**
- 上传文件名 `../../evil.txt`、`..\\..\\evil.txt`、`/etc/passwd.txt`、`C:\\Windows\\evil.txt` 均被拒绝（400）或仅落盘为 uploadDir 下的纯文件名，`uploads/` 目录外无任何新文件；
- 文件名为 `..`、`.`、含 `"`、空格、控制字符时返回 400；
- 正常中文名 `磁盘告警处置.md` 上传、去重覆盖、自动索引链路行为不变；
- 单元化验证可直接对 `upload()` 内净化逻辑做断言（测试补齐属 P1，本期至少手工脚本验证）。

---

### T3｜P0-高｜Milvus 过滤表达式注入修复

**涉及文件**
- `src/main/java/org/example/service/VectorIndexService.java`（`deleteExistingData(String filePath)` 第 173-215 行，问题点第 181 行；`insertToMilvus()` 第 270-271 行的 id 生成逻辑为长期方案依据）

**背景（攻击面）**：`deleteExistingData` 的入参路径最终来源于上传文件名（T2 攻击面），拼进 `metadata["_source"] == "<path>"` 后用于 **delete**。文件名含 `"` 或 `\` 时可改变表达式语义（如 `x" || true || metadata["_source"] == "y`），造成误删全库数据。

**具体改动**（双层防护，本期落地）：
1. **字面量转义**：新增工具方法（放 `VectorIndexService` 私有静态方法或独立 `MilvusExprEscaper`，P2 批量索引复用）：
   ```java
   static String escapeExprString(String raw) {
       return raw.replace("\\", "\\\\").replace("\"", "\\\"");
   }
   // 第 181 行改为：
   String expr = String.format("metadata[\"_source\"] == \"%s\"", escapeExprString(normalizedPath));
   ```
2. **来源白名单**：进入 `deleteExistingData` 的路径其文件名段必须匹配 `SAFE_FILENAME_REGEX`（与 T2 同一常量），不匹配直接拒绝删除并告警（防御纵深：即使上游绕过 T2，表达式也拼不进非法字符）。
3. 日志（第 183 行）打印的 expr 保留但确保不含控制字符（转义后即满足）。

**验收标准**
- 构造文件名 `a".txt`（含双引号）上传：表达式经转义后按**精确路径**删除旧数据，删除记录数只影响该文件自己的 chunk，不出现表达式解析错误、不误删其他 `_source`；
- 构造 `x" || true || metadata["_source"] == "y.md` 类注入名：被 T2 白名单拦截（400）；若直接调用 `deleteExistingData`，被 T3 白名单拦截并告警；
- 正常文件重复上传（覆盖更新）时旧 chunk 被清理、新 chunk 写入，行为与改造前一致。

---

### T4｜P0-中｜日志泄露清理（密钥 / 完整向量）

**涉及文件**
- `src/main/java/org/example/service/VectorEmbeddingService.java`（第 42、61、91-92、106 行）

**具体改动**（原则：日志中不出现任何密钥字节与向量全文）
| 行号（快照） | 现状 | 改法 |
|---|---|---|
| 42 | `logger.error("API Key 未正确配置！当前值: {}", apiKey);` 打印**完整 Key** | 删除 `{}` 取值，改为 `logger.error("API Key 未正确配置（dashscope.api.key 缺失或为空）");` |
| 61 | `logger.info("Constants.apiKey 已设置: {}", ...前 8 位...)` | 整行删除（Key 片段也不落日志）；保留"设置成功"语义可用固定文案 |
| 91-92 | `logger.debug("调用 API 前 Constants.apiKey: {}", ...前 8 位...)` | 整段删除 |
| 106 | `logger.debug("API5 返回向量嵌入: {}", floatEmbedding);` 打印**完整向量** | 改为 `logger.debug("返回向量嵌入, 维度: {}", floatEmbedding.size());`（第 108-109 行已有等价 info，可直接删除 106） |

配套：第 47-50 行 `maskedKey`（前 8 + 后 4）虽已脱敏，为统一口径一并删除，避免不同脱敏标准共存。校验失败信息只报键名（与 T1 一致）。

**验收标准**
- 全代码检索 `logger.*(.*[Kk]ey`、`floatEmbedding` 的打印语句，无任何 Key 字节/向量元素输出；
- DEBUG 级别全开跑一轮上传+检索，日志中检索不到 `sk-` 片段与浮点数组；
- 功能不变（embedding 生成、维度校验正常）。

---

### T5｜P0-中｜前端 XSS 修复（DOMPurify 清洗）

**涉及文件**
- `src/main/resources/static/app.js`（`renderMarkdown()` 第 62-78 行；`innerHTML` 赋值点第 749、762、772、788、842、1347、1421 行）
- `src/main/resources/static/index.html`（引入 DOMPurify，本地文件，见 T7）

**具体改动**
1. `renderMarkdown()` 输出统一过 DOMPurify（依赖本地化，见 T7）：
   ```js
   renderMarkdown(content) {
       if (!content) return '';
       if (typeof marked === 'undefined') { return this.escapeHtml(content); }
       try {
           const html = marked.parse(content);
           // 白名单式清洗：过滤 script/onerror/javascript: 等
           return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
       } catch (e) {
           return this.escapeHtml(content);
       }
   }
   ```
   所有 `messageContent.innerHTML = this.renderMarkdown(...)` 调用点无需逐个改，天然被覆盖（包括第 762 行 SSE 错误消息、第 1347 行 AI Ops 结果）。
2. 非 Markdown 的 HTML 拼接点核查结论：
   - 第 393-402 行 `historyItem.innerHTML` 已用 `this.escapeHtml(history.title)`（第 395 行），保持现状；
   - 第 1329、1403 行 `detailItem.innerHTML` 已用 `escapeHtml(detail)`，保持现状；
   - 第 825、877、1372 行头像/加载图标为静态模板，无注入面；
   - 结论：入口收敛到 `renderMarkdown()` 一处即可。
3. `marked.setOptions`（第 27-47 行）中的 `headerIds/mangle` 等已关闭 HTML 相关特性，保留不动。

**验收标准**
- 上传知识库内容含 `<img src=x onerror=alert(1)>`、`[x](javascript:alert(1))`、`<script>alert(1)</script>`、`<iframe src=...>`，在对话回答中渲染时：脚本不执行、`onerror` 等属性被剥离、正常 Markdown（标题/代码块/高亮/表格）渲染效果不回退；
- 浏览器 Console 无 CSP/XSS 报错，`hljs` 代码高亮仍工作。

---

### T6｜P0-中｜最小可用鉴权（X-API-Key 过滤器）+ CORS 收紧

**涉及文件**
- 新增（允许）：`src/main/java/org/example/config/ApiKeyAuthFilter.java`（`OncePerRequestFilter`，或注册于 WebMvcConfig 的 `HandlerInterceptor`，二选一，见 D2）
- `src/main/java/org/example/config/WebMvcConfig.java`（第 16-22 行 CORS 配置）
- `src/main/resources/application.yml`（新增 `security.api-key`、`security.allowed-origins`）
- `src/main/resources/static/app.js`（第 4 行 `apiBaseUrl`；4 处 `fetch`：第 608、664、1060、1105 行补 Header）
- `src/main/resources/static/index.html`（首次使用输入 Key 的轻量交互，可选）

**具体改动**
1. **鉴权过滤器**（不引 Spring Security，纯 Servlet 过滤器，零新依赖）：
   ```java
   @Component
   public class ApiKeyAuthFilter extends OncePerRequestFilter {
       @Value("${security.api-key}") private String apiKey;

       @Override
       protected boolean shouldNotFilter(HttpServletRequest req) {
           String p = req.getRequestURI();
           return p.equals("/") || p.equals("/index.html") || p.equals("/app.js")
               || p.equals("/styles.css") || p.startsWith("/vendor/") || p.equals("/error");
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
               res.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
           }
       }
   }
   ```
   要点：恒定时间比较（`MessageDigest.isEqual`）防时序侧信道；只放行静态资源，`/api/**`、`/milvus/**` 全部要求 Header；过滤器对 SSE（`SseEmitter`，`/api/chat_stream`、`/api/ai_ops`）同样生效（`preHandle`/过滤器在响应提交前执行）。
2. **CORS 收紧**：`WebMvcConfig.addCorsMappings()` 中 `allowedOrigins("*")` 改为读取 `security.allowed-origins`（逗号分隔，部署时给明确来源；同源部署时可为空数组）。`allowedHeaders` 收窄为 `Content-Type, X-API-Key`。
3. **前端配合**：
   - `app.js` 第 4 行 `this.apiBaseUrl = 'http://localhost:9900/api'` 改为相对路径 `'/api'`（消除跨域前提 + 环境硬编码）；
   - 4 处 `fetch` 统一带 `'X-API-Key': this.apiKey`，`this.apiKey` 首次使用时提示输入并存 `sessionStorage`（避免把 Key 硬编码进源码；静态页面本身同源免 Key）。
4. **取舍说明（写给评审）**：该方案只解决"匿名扫描/盗刷 LLM 费用/删数据"，**不提供**用户身份、权限分级、审计、防重放；前端持有 Key 属"共享口令"模型。完整 Spring Security/OAuth2/登录体系明确留待 P3（见 2.2），届时本过滤器可平滑替换为 Security 过滤链而不改 Header 约定。

**验收标准**
- 不带 `X-API-Key`：`curl -X POST http://localhost:9900/api/chat` → HTTP 401 + `{"code":401,"message":"unauthorized","data":null}`；`/api/upload`、`/api/ai_ops`、`/milvus/health` 同样 401；
- 带错误 Key → 401；带正确 Key → 行为与改造前一致（含 SSE 流式、文件上传）；
- 浏览器直接打开 `/`（静态页）不需要 Key，页面发起的 API 调用带 Key 后全链路可用；
- 外部 Origin（非 `security.allowed-origins` 白名单）的跨域请求被拒（无 CORS 响应头）；
- `curl -X OPTIONS` 预检只允许配置的来源与 `Content-Type, X-API-Key` 头。

---

### T7｜P0-中｜前端 CDN 依赖本地化（含 DOMPurify）与完整性校验

**涉及文件**
- `src/main/resources/static/index.html`（第 9-12 行 jsdelivr 引用）
- 新增（允许）：`src/main/resources/static/vendor/marked.min.js`（marked 11.1.1）、`vendor/highlight.min.js` 与 `vendor/github.min.css`（highlight.js 11.9.0）、`vendor/purify.min.js`（DOMPurify 3.x，T5 依赖）

**具体改动**
1. 将 `https://cdn.jsdelivr.net/npm/marked@11.1.1/marked.min.js`、`highlight.js@11.9.0/es/highlight.min.js`、`styles/github.min.css` 下载为本地 `static/vendor/` 文件，`index.html` 改为本地引用：
   ```html
   <script src="vendor/marked.min.js"></script>
   <link rel="stylesheet" href="vendor/github.min.css">
   <script src="vendor/highlight.min.js"></script>
   <script src="vendor/purify.min.js"></script>
   ```
2. **供应链校验**：下载后记录各文件 SHA-256 于 `static/vendor/README` 或本方案附录（内网无 CDN 时 SRI `integrity` 属性可继续用于本地文件，建议保留 `integrity` + `crossorigin="anonymous"`；至少保留哈希记录以便审计比对）。若个别环境仍走 CDN，必须带 `integrity`（SRI）与 `crossorigin`。
3. 新引入的前端静态依赖仅 **DOMPurify 3.x** 一个，理由见 D5；版本锁死（不使用 `@latest`）。

**验收标准**
- 断外网（或浏览器 DevTools 屏蔽 jsdelivr 域名）后强刷页面：Markdown 渲染、代码高亮、XSS 清洗全部正常，Network 面板无任何外域请求；
- `index.html` 中无 `cdn.jsdelivr.net` 等外域 URL；
- `vendor/` 各文件哈希与记录一致。

---

### T8｜P0-中｜手拼 JSON 未转义修复（统一 Jackson 序列化）

**涉及文件**
- `src/main/java/org/example/agent/tool/QueryLogsTools.java`（`buildErrorResponse(String)` 第 592-601 行，问题点第 599 行）
- `src/main/java/org/example/agent/tool/QueryMetricsTools.java`（`buildErrorResponse(String,String)` 第 223-233 行，问题点第 231 行）
- `src/main/java/org/example/agent/tool/InternalDocsTools.java`（`queryInternalDocs(String)` 第 63-77 行，问题点第 75-76 行；第 64 行静态字面量一并治理）

**具体改动**（三个类均已持有 Jackson `ObjectMapper objectMapper` 字段，无需新依赖）：
1. `QueryLogsTools.buildErrorResponse`：主路径已是 `objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output)`（第 597 行），保留；**catch 兜底改为不插值的安全常量**，杜绝 `message` 中引号/换行破坏 JSON：
   ```java
   } catch (Exception e) {
       logger.error("错误响应序列化失败", e);
       return "{\"success\":false,\"message\":\"error response serialization failed\"}";
   }
   ```
2. `QueryMetricsTools.buildErrorResponse` 第 231 行同法处理（兜底常量不含 `message`/`error` 插值）。
3. `InternalDocsTools.queryInternalDocs`：异常分支（第 75-76 行）改为 Jackson 序列化 `Map`：
   ```java
   } catch (Exception e) {
       logger.error("[工具错误] queryInternalDocs 执行失败", e);
       return objectMapper.writeValueAsString(
           Map.of("status", "error", "message", "Failed to query internal docs: " + e.getMessage()));
   }
   ```
   第 64 行无结果分支同法改为 `Map.of("status","no_results","message",...)` 序列化，消灭裸 JSON 字面量。
4. 原则写入第 5 节约定：**工具返回值 JSON 一律 Jackson 序列化，禁止 `String.format`/字符串拼接构造 JSON**（P1 统一异常体系沿用）。

**验收标准**
- 触发错误路径且错误消息含 `"`、`\`、换行（如 mock 异常消息 `bad "quote" \nline`）：三个工具返回值均可被 `ObjectMapper.readValue` 成功解析，字段完整；
- 正常路径返回 JSON 结构（`success/logs/total`、`status` 等字段）与改造前一致，LLM 工具调用行为不变；
- 全代码检索 `String.format("` + JSON 花括号模式，业务代码中无残留。

---

## 4. 关键技术决策

### D1 密钥外置方式：环境变量 vs KMS/Vault vs 配置中心
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 环境变量 + 无默认值占位符（**选定**） | 零新增依赖；Spring 启动期解析失败即退出，天然 fail-fast；与现有 `@Value` 消费方式（`VectorEmbeddingService` 第 30 行）完全兼容；部署脚本/K8s Secret 均可注入 | 变量可见于进程环境（同主机高权限可读）；无自动轮换 |
| B. Vault/KMS + 动态密钥 | 轮换自动化、审计、托管 | 引入新组件与网络依赖，P0 时间窗内改动面大，违背"止血"目标 |
| C. 配置中心（Nacos/Apollo 等） | 集中管理 | 新增基础设施依赖，同 B |

**选择 A**：P0 只要求"密钥不落库、可轮换、缺失即拒启"。B/C 列入 P2/P3 演进项，配置键名（第 5 节）保持不变，迁移时只换注入方式。

### D2 鉴权：自定义 `OncePerRequestFilter` vs 完整 Spring Security vs 网关层
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 自定义过滤器（**选定**） | 零新依赖（`spring-boot-starter-web` 已含 Servlet API）；~40 行代码可控；Header 约定可原样延续到 P3 | 无用户体系/RBAC/审计；前端持共享口令 |
| B. Spring Security 全家桶 | 功能完整、生态标准 | 引入 `spring-boot-starter-security` + 配置链改造，与 SSE/静态资源放行的细节多，超出"最小止血"范围；易在 P0 引入回归 |
| C. Nginx/网关层鉴权 | 应用零改动 | 属基础设施层，本仓库不可交付；开发/联调环境仍是裸奔 |

**选择 A，并显式声明取舍**（防匿名滥用 + 盗刷 LLM 费用 + 误删数据，非身份认证）。P3 上用户体系时替换为 Security 过滤链，`X-API-Key` Header 与 401 错误体格式不变，前端无需再改。

### D3 路径穿越修复：仅取文件名 + 白名单 + startsWith vs UUID 重命名 vs 业务目录隔离
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 净化文件名 + 白名单 + `startsWith`（**选定**） | 保留代码注释（`FileUploadController` 第 58 行）明确要求的"基于文件名去重/覆盖"语义；改动局部 | 文件名白名单可能拒绝极冷门字符（已含中文，可接受） |
| B. UUID 重命名存储 | 彻底消除文件名影响 | 破坏按文件名去重与 `_source` 语义，牵连 `VectorIndexService.indexSingleFile`、`FileUploadRes`、后续删除链路，超出 P0 范围 |
| C. 按用户/日期分目录 | 结构清晰 | 未解决单目录内的穿越问题，仍需 A 的校验 |

**选择 A**：三层防御（剥离路径成分 → 字符白名单 → `startsWith` 权威兜底）逐层独立有效；B 留给 P2 索引重构时评估。

### D4 Milvus 表达式注入：转义 + 白名单 vs 参数化/字段化 vs 按 id 删除
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 字面量转义 + 来源白名单（**选定，本期**） | 改动一行 + 一个工具方法；delete 语义（按 `_source` 精确匹配）不变 | 仍是表达式拼接，依赖转义正确性；防御纵深靠白名单 |
| B. 独立 `_source` 标量字段 + 建索引 | 过滤更规范、可被 P2 批量索引复用 | 需要重建 Collection/迁移存量数据，超出止血范围 |
| C. 按确定性 id 删除（`insertToMilvus` 第 271 行 `UUID.nameUUIDFromBytes(source + "_" + chunkIndex)`） | 完全无表达式 | 删除旧数据需先知道旧 chunk 数（`totalChunks`），需额外查询或元数据表，逻辑改动大 |

**选择 A 落地、B 作为 P2 目标**（见第 5 节接口约定：转义方法 `escapeExprString` 与 `SAFE_FILENAME_REGEX` 为公共契约）。

### D5 XSS：DOMPurify vs 自写 sanitize vs 纯 textContent
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. DOMPurify（**选定**，本地化引入） | 事实标准、白名单式清洗、维护活跃；保留 Markdown 渲染体验 | 新增 1 个前端静态文件（无后端依赖） |
| B. 自写正则/过滤 | 无依赖 | 易被绕过（svg/math/mXSS 变体），安全组件不建议自研 |
| C. 全部 `textContent` | 最安全 | Markdown 代码块/高亮/表格全部失效，产品体验倒退 |

**选择 A**：`renderMarkdown()` 单点收敛 + DOMPurify，配合 T7 本地化（内网可离线加载）。`escapeHtml`（`app.js` 第 1435-1440 行附近）保留用于纯文本场景。

### D6 JSON 构造：Jackson vs Gson vs 手写转义
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. Jackson（**选定**） | Spring Boot 默认栈；三个工具类已持有 `ObjectMapper` 字段；`WebConfig`（第 34-37 行）已注册 Bean；`pom.xml` 已管理 2.17.0 | — |
| B. Gson | 工程已有（`pom.xml` 第 102-106 行，`VectorIndexService.insertToMilvus` 第 286-288 行用于 Milvus metadata） | 两套 JSON 栈并存，语义/配置（Java 8 时间等）不一致 |
| C. 手写转义 | 无依赖 | 即本次事故根因，直接排除 |

**选择 A**：错误兜底改为"固定常量 JSON + 记日志"，正常路径 Jackson POJO 序列化。Gson 仅保留给 Milvus SDK 交互（P2 可统一，不在 P0 动）。

### D7 前端依赖：本地化 vs CDN + SRI
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 本地化 `static/vendor/`（**选定**） | 内网/离线可用（硬性需求）；无 CDN 供应链与可用性风险 | 需自行管理版本升级与哈希记录 |
| B. CDN + SRI | 零文件维护 | 企业内网不可达（当前 jsdelivr 直接白屏）；SRI 只防篡改不防不可用 |

**选择 A**；SRI `integrity` 属性与 SHA-256 记录作为本地文件的完整性校验手段保留（B 仅作为可选外网环境的降级方案，且必须带 SRI）。

## 5. 与其它阶段的依赖关系（P1/P2/P3 的接口约定）

以下为 **P0 产出的对外约定**，P1（工程化底座）/P2（性能与扩展）/P3（产品化）方案必须原样沿用：

### 5.1 配置与环境变量约定
| 环境变量 | 配置键 | 含义 | 引入任务 |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `spring.ai.dashscope.api-key`、`dashscope.api.key` | DashScope LLM + Embedding 共用密钥（**无默认值**） | T1 |
| `MCP_TENCENT_CLS_SSE_ENDPOINT` | `spring.ai.mcp.client.sse.connections.tencent-cls.sse-endpoint` | MCP SSE 端点（路径含凭证） | T1 |
| `APP_API_KEY` | `security.api-key` | 本服务对外 API Key | T6 |
| （逗号分隔列表） | `security.allowed-origins` | CORS 白名单 | T6 |
| — | `file.upload.path` / `file.upload.allowed-extensions` | 沿用现有键（`FileUploadConfig`） | 不变 |
| — | `SAFE_FILENAME_REGEX`（代码常量：`[A-Za-z0-9\u4e00-\u9fa5._-]{1,128}`） | 文件名白名单，上传/删除/Milvus 拼接共用 | T2/T3 |

规则：**敏感值一律无默认值占位符 `${VAR}`**；新增敏感配置照此办理（P2 的 Redis 密码、P3 的数据源凭证同理）。

### 5.2 鉴权与错误响应约定
- 鉴权 Header：`X-API-Key: <APP_API_KEY>`；缺失/错误 → **HTTP 401**。
- 统一错误响应体（沿用 `FileUploadController.ApiResponse` 与 `ChatController.ApiResponse` 的 `code/message/data` 形状）：
  ```json
  {"code": 401, "message": "unauthorized", "data": null}
  ```
  HTTP 状态码语义：`400` 参数/文件名非法、`401` 未鉴权、`500` 服务内部错误。P1 的统一异常体系（GlobalExceptionHandler）需输出同形状；`message` 为人类可读文本，`data` 成功时为业务对象、失败时为 `null`。
- 静态资源（`/`、`/index.html`、`/app.js`、`/styles.css`、`/vendor/**`、`/error`）免鉴权；`/api/**`、`/milvus/**` 强制鉴权（P3 新增管理接口默认纳入）。
- 前端取 Key 方式：`sessionStorage`（P3 换登录态时移除，Header 不变）。

### 5.3 代码级约定
- **JSON 构造一律 Jackson**：禁止 `String.format`/拼接构造 JSON（含工具返回值、异常兜底、SSE 事件体）。兜底异常体用固定常量 + `logger.error`。
- **日志脱敏**：任何日志不得打印密钥（含片段）、完整向量、完整凭证 URL。P1 接入可观测/链路追踪时沿用；如需排障只打键名/维度数。
- **Milvus 表达式**：任何进入 `withExpr` 的字符串字面量必须经 `escapeExprString()`（`\"`、`\\` 转义）且来源文件名匹配 `SAFE_FILENAME_REGEX`。P2 批量/异步索引、P3 重建索引功能复用同一工具类。
- **文件落盘**：上传文件名必须经"剥离路径成分 + 白名单 + `startsWith(uploadDir)`"三重校验，P3 管理界面上传通道复用同一净化方法（建议 P1 抽取 `FilenameSanitizer` 公共类）。
- **CORS**：默认拒绝跨域；来源只从 `security.allowed-origins` 读取，不允许回退 `*`。

### 5.4 阶段衔接
- **P1（测试/CI/可观测/统一异常）**：T2/T3/T8 的修复点是单测重点（文件名净化、表达式转义、错误 JSON 可解析性）；统一异常体系接管 401/400/500 错误体；日志脱敏规则进入 code review checklist 与 CI 静态检查（可加简单 grep 门禁：禁 `sk-` 明文入库）。
- **P2（批量化/异步索引/Redis 会话）**：批量索引沿用 `escapeExprString` 与文件名白名单；异步索引产生的错误 JSON 同样走 Jackson；Redis 连接串属敏感配置，按 5.1 规则无默认值外置。
- **P3（真实数据源/管理界面/RAG 评估）**：管理界面复用 `X-API-Key`（或升级为登录态，Header 保留兼容）；真实数据源（CLS/Prometheus）的接入凭证按 5.1 外置；按 D4-B 的 `_source` 字段化在索引重构时落地。

## 6. 验证方案

### 6.1 逐项验证
| 任务 | 验证方法 |
|---|---|
| T1 | ① 仓库/交付物全文检索 `sk-ws-`、`/sse/92`，命中数为 0；② unset `DASHSCOPE_API_KEY` 启动 → 启动失败且日志指明缺失键名；③ 置好变量启动 → `/api/chat` 一次正常问答、上传一次正常 embedding；④ 控制台确认旧 Key 已禁用、新 Key 调用计数正常 |
| T2 | `curl -X POST /api/upload -F "file=@x.txt;filename=../../evil.txt"` 等一组攻击文件名（`../../evil.txt`、`..\\..\\evil.txt`、`/etc/x.txt`、`C:\\x.txt`、`..`、`a b".txt`），断言：400 或仅落盘 uploadDir 内；`find` 确认 uploadDir 外无新文件；正常中文名文件上传+索引回归 |
| T3 | 上传文件名含 `"`（如 `a".txt`）与注入串（`x"\|\|true\|\|metadata["_source"]=="y.md`）；在 Milvus（`milvus/health`、或 `query` 按 `_source` 统计）确认：无表达式错误、其他文件 chunk 数量不变、仅目标文件旧数据被删；重复上传同名文件覆盖更新回归 |
| T4 | 启动 + 上传 + 检索全流程，DEBUG 级别日志全文检索 `sk-`、`Constants.apiKey`、浮点数组模式，命中 0；功能回归 embedding 维度正常 |
| T5 | 知识库写入 `<img src=x onerror=alert(1)>`、`<script>alert(1)</script>`、`[x](javascript:alert(1))`、`<iframe>`，对话引用后浏览器断言：无弹窗、DOM 中无 `onerror`/`javascript:`；标题/代码高亮/表格渲染对比截图无回退 |
| T6 | 无 Key/错 Key/对 Key 三组 `curl` 覆盖 `/api/chat`、`/api/chat_stream`、`/api/upload`、`/api/ai_ops`、`/milvus/health`；SSE 流式在带 Key 下正常收流；跨域 Origin 白名单内外各测一次（含 OPTIONS 预检）；前端全流程（对话/流式/上传/AI Ops）可用 |
| T7 | 断外网或 DevTools 屏蔽 jsdelivr 后强刷：渲染/高亮/清洗正常，Network 无外域请求；`vendor/` 哈希比对一致 |
| T8 | 构造含 `"`、`\`、换行的错误消息触发三个工具的错误分支，返回值 `readValue` 反序列化成功；正常路径返回结构与改造前 diff 一致 |

### 6.2 上线前检查清单（发布门禁）
- [ ] `application.yml`（及一切副本/镜像层/交付压缩包）无 `sk-` 明文、无完整 MCP 端点 token，仅有 `${VAR}` 占位符
- [ ] 旧 DashScope Key、旧 MCP 端点凭证已禁用/重置，新值仅存于部署环境变量（K8s Secret / 启动脚本权限收敛）
- [ ] 未配置密钥时服务启动失败并明确报错（fail-fast 已验证）
- [ ] `/api/**`、`/milvus/**` 匿名请求全部 401，静态页正常打开
- [ ] `security.allowed-origins` 已按生产域名配置，非白名单跨域被拒
- [ ] 攻击文件名上传验证通过（T2/T3 payload 全套）
- [ ] XSS payload 渲染验证通过，前端无外域请求（T5/T7）
- [ ] DEBUG 日志开一轮无密钥/向量泄露
- [ ] 三个 Agent 工具错误分支返回合法 JSON
- [ ] 本方案第 5 节"对外约定"已同步至 P1/P2/P3 方案文档

## 7. 风险与应对

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | 密钥轮换顺序不当（先禁旧 Key）导致服务中断 | 线上 LLM/embedding 全挂 | 严格按 T1 顺序"先上新、后禁旧"；切换窗口内新旧 Key 并存；保留回滚环境变量 |
| 2 | 已泄露 Key 在轮换前被滥用 | 费用盗刷 | 轮换为第一优先级；控制台查看调用明细，异常计费立即申诉；后续接入用量告警（P2 可观测） |
| 3 | 鉴权上线后前端/脚本未带 Key 全部 401 | 功能不可用 | T6 与前端改造同批发布；发布前用 6.1 三组 curl + 前端全流程回归；对外提供的接口文档同步补 Header 说明 |
| 4 | 前端持共享 Key，被内网用户提取后互相冒用 | 仅能防匿名外部滥用，非强身份 | 明确接受（D2 取舍）；P3 上用户体系替换；Key 可随时轮换（配置一处生效） |
| 5 | 文件名白名单误拒合法名（空格、生僻字、超长） | 上传体验下降 | 白名单已含中文与 `._-`；拒绝时返回明确 400 文案；如需扩展仅调 `SAFE_FILENAME_REGEX` 单点 |
| 6 | Milvus 转义遗漏某种语法（如 `\'`、多级 JSON 路径）导致误删 | 数据丢失 | 转义 + 白名单双层（白名单已排除引号/反斜杠）；上线前用注入 payload 全套验证（6.1 T3）；删除前日志记录 expr 与删除条数（现有第 183、209 行）便于事后审计；建议上线前对 Milvus Collection 做一次备份快照 |
| 7 | DOMPurify 清洗过严导致 Markdown 渲染差异（如代码高亮类名被清） | 展示回退 | 采用 `USE_PROFILES: { html: true }`；与改造前渲染结果做逐类型（标题/代码/表格/链接）对比验收（6.1 T5） |
| 8 | 本地化前端库版本固定后不再随 CDN 自动更新 | 已知漏洞不被自动修复 | `vendor/` 哈希与版本登记在案（D7）；依赖升级纳入 P1 的例行检查（升级时重新做 XSS 验收） |
| 9 | 错误兜底改为常量 JSON 后，排障丢失原始错误信息 | 排障困难 | 原始 `message` 由 `logger.error` 完整落日志（T8），返回给 LLM/前端的是安全常量，信息不丢失、只是换了出口 |
| 10 | 启动 fail-fast 误伤本地开发（忘设环境变量起不来） | 开发效率 | 提供 `application.yml.example` 与 README 启动说明；本地可用 IDE 运行配置注入环境变量；不允许回退"默认值"弱化安全 |
