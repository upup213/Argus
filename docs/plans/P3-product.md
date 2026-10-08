# P3 阶段方案：产品化

> 适用工程：`d:\代码\SuperBizAgent-release-2026-05-17`（Maven 单模块，Java 17 + Spring Boot 3.2.0 + Spring AI Alibaba 1.1.0.0-RC2 + DashScope（通义）+ Milvus 2.6.x，入口 `org.example.Main`，前端为 `src/main/resources/static` 下原生 HTML/JS）。
> 前置：P0（安全止血，见 `docs/plans/P0-security.md`）、P1（工程化底座）、P2（性能与扩展，见 `docs/plans/P2-performance.md`）已定约定，本方案第 5 节声明沿用情况。
> 文中所有路径、行号、方法名均基于当前代码快照逐项核实；行号在后续修改后会漂移，以"文件 + 方法/代码特征"为准。

## 1. 背景与目标

### 1.1 背景

P0 消除安全硬阻塞、P1 建立工程化底座、P2 完成性能与扩展改造后，服务在"安全、可扩展"上已达标，但仍是"演示级"产品——**真实数据源未接通（日志/告警全是 Mock）、知识库无管理界面、前端交互粗糙、检索质量无度量、无审计**。经逐项核查源码，当前与"产品化"相关的差距如下：

| # | 问题 | 核实位置（当前快照） |
|---|------|----------------------|
| 1 | 日志查询真实分支是占位错误文案，仅 Mock 假数据 | `src/main/java/org/example/agent/tool/QueryLogsTools.java` 第 179-182 行（第 181 行 `buildErrorResponse("如果配置文件没有用mock，则按照spring-ai-starter-mcp-client-webflux的规范自动注入mcp tool,不会走进来")`）；假数据生成器 `buildMockLogs`（210-247）、`buildSystemMetricsLogs`（252-337）、`buildApplicationLogs`（342-457）、`buildDatabaseSlowQueryLogs`（462-519）、`buildSystemEventsLogs`（524-567）、`buildGenericLogs`（572-587）；`src/main/resources/application.yml` 第 46-50 行 MCP SSE 端点打码占位（第 50 行 `sse-endpoint: /sse/92XXXXXXXXb4`） |
| 2 | 告警 `buildMockAlerts` 硬编码 3 条假告警；`prometheus.base-url` 为 localhost 占位 | `QueryMetricsTools.java` 第 131-171 行（`buildMockAlerts`）；`application.yml` 第 72 行 `base-url: http://localhost:9090`；"相同 alertname 只保留第一个"去重逻辑在第 79-92 行 |
| 3 | `RagService` 是死代码（无 Controller 引用），reasoning 提取半成品，模型配置两处不一致 | `src/main/java/org/example/service/RagService.java`（全工程仅本类引用，grep 无 Controller/Service 调用方）；第 187 行 `StringBuilder reasoningContent` 从未写入、第 219 行以空串传给 `onComplete`、第 228 行 `onReasoningChunk` 从未调用；`application.yml` 第 68 行 `rag.model=qwen3-max` 与 `RagService.java` 第 42 行默认值 `qwen3-30b-a3b-thinking-2507` 不一致 |
| 4 | 无知识库管理界面：既看不到"已索引文档"，也无删除/重建入口 | 当前仅 `VectorIndexService.indexDirectory()`（第 54-116 行）与 `indexSingleFile()`（124-168 行），无按文档查询/删除接口；删除依赖 `deleteExistingData`（173-215 行）但无对外入口 |
| 5 | 前端 API 地址写死、会话存 localStorage、单行输入、SSE 无重试/停止、上传无进度 | `src/main/resources/static/app.js` 第 4 行 `this.apiBaseUrl = 'http://localhost:9900/api'`、第 357-365 行（第 359 行 `localStorage.getItem('chatHistories')`）、`sendStreamMessage`（662-802）/`sendAIOpsRequest`（1103-1237）无 `AbortController`、`uploadFile`（1035-1091）用 fetch 无进度；`index.html` 第 62 行 `<input ... maxlength="1000">` 单行 |
| 6 | 无 RAG 质量评估：召回率、答案相关性无度量，坏例无回流 | 检索链路 `VectorSearchService.searchSimilarDocuments()`（42-94 行）无任何评测埋点 |
| 7 | 无审计：谁在何时问了什么、删了哪些文档不可追溯 | 全工程无审计日志/审计落库 |

### 1.2 目标

1. **接通真实数据源**：日志接腾讯云 CLS、告警接真实 Prometheus，Mock 数据归档或删除，正式开关语义明确。
2. **RAG 链路收尾**：对 `RagService` 做出"接入或删除"决策；若接入，暴露 `/api/rag` 接口、补齐 reasoning 流式、统一模型配置。
3. **知识库可管理**：复用 P2 索引任务 API 与 `VectorRepository`，新增文档列表/删除/重建接口 + 前端管理页。
4. **前端体验达标**：API 地址可配置、textarea 交互、SSE 错误态/重试/停止、上传进度条、会话对接 Redis。
5. **检索质量可度量**：评测问题集 + 召回率@k + 答案相关性 + 评估脚本，坏例回流。
6. **轻量多用户与审计**：给出用户体系取舍；落地结构化审计日志。

### 1.3 技术栈边界

新增依赖需说明理由（见第 4 节）：后端候选 `tencentcloud-sdk-java-cls`（CLS 直连）；前端**不引构建工具**（D3 决策）。其余一律沿用现有栈（Java 17、Spring Boot 3.2.x、Maven、Jackson、SLF4J、OkHttp）。

## 2. 范围界定（本期做什么 / 明确不做什么）

### 2.1 本期做什么

| 任务 | 内容 |
|---|---|
| T1 | 日志接入腾讯云 CLS（SDK 直连，D1 决策）：实现 `queryLogs` 真实分支、`cls.mock-enabled` 语义与 profile 策略、假数据归档删除 |
| T2 | 告警接入真实 Prometheus：`prometheus.base-url`/凭证环境变量化、去重策略评审、删除硬编码假告警 |
| T3 | `RagService` 接入（D2 决策）：暴露 `/api/rag`（SSE）、补齐 reasoning 流式、统一 `rag.model` 配置 |
| T4 | 知识库文档管理接口：文档列表（复用 `VectorRepository`）、删除（同步删向量）、重建（复用 P2 索引任务 API） |
| T5 | 知识库管理前端页面：文档列表、状态徽标、删除/重建按钮 |
| T6 | 前端工程化与交互：API 地址配置注入、textarea、错误态+重试+停止、上传进度、会话对接 Redis |
| T7 | RAG 质量评估：评测集 + 召回率@k + 答案相关性 + 评估脚本 + 坏例回流 |
| T8 | 轻量多用户与审计：用户体系取舍（D6）、结构化审计日志 |

### 2.2 明确不做什么

- **不做完整用户系统 / 登录 / SSO / RBAC**：内部运维单租户工具，共享 `X-API-Key` 够用（D6 取舍）；仅提供"多 Key 映射操作者名"的轻量归属，供审计用。
- **不做检索算法升级**（混合检索 BM25+向量、rerank、多路召回、query 改写）：P2 已明确留 P3，但本期目标是"度量现有检索质量"而非"换检索算法"；评测（T7）若发现召回不足，另立专项评估。
- **不引入消息队列、不做多租户/分 collection 多库**（`milvus.collection-name` 配置化能力由 P2 已备，本期不扩展多库语义）。
- **不做 LLM 微调、不做模型评测平台、不做对话内容语义缓存**。
- **不做移动端 / 小程序 / 桌面端**；前端只做列出的交互改造，不做整体重构（除非 T6 的"原生 ES Modules 拆分"这类零构建工具改造）。
- **不新增关系型/文档型数据库**（审计用结构化日志，D5 决策）。

### 2.3 建议实施顺序（依赖驱动）

```
T1（CLS 真实接入，数据源地基）
 → T2（Prometheus 真实接入，与 T1 并列可并行）
 → T3（RagService 接入，依赖 P2 的 VectorRepository/COSINE 已落地）
 → T4（文档管理接口，依赖 P2 VectorRepository + 索引任务 API）
 → T5（管理前端，依赖 T4）
 → T6（前端工程化与体验，独立于 T4/T5，可与 T3/T4 并行）
 → T7（评测体系，依赖 T1/T2/T3 真实数据与 /api/rag 入口）
 → T8（审计与轻量多用户，收尾，覆盖全部接口动作）
```

## 3. 任务清单

> 优先级定义：**P3-高**（产品化的硬前提：真实数据、RAG 可用、知识库可管理）｜**P3-中**（体验与质量度量）｜**P3-低**（审计与多用户归属）。

---

### T1｜P3-高｜日志接入腾讯云 CLS（SDK 直连）

**目标**：`queryLogs` 真实分支落地，打通"Agent → 查日志 → 返回真实 CLS 日志"链路；明确 `cls.mock-enabled` 语义；清理约 380 行假数据。

**涉及文件**
- `src/main/java/org/example/agent/tool/QueryLogsTools.java`（第 161-203 行 `queryLogs` 真实分支、第 210-587 行 `build*Logs` 假数据、第 57-132 行 `getAvailableLogTopics`）
- `src/main/java/org/example/service/ChatService.java`（第 41-42 行 `@Autowired(required=false) QueryLogsTools`、第 123-131 行 `buildMethodToolsArray` 的"mock/真实二选一"逻辑、第 97 行系统提示词"调用腾讯云mcp服务"文案）
- 新增（允许）：`src/main/java/org/example/client/ClsLogClient.java`（封装 CLS SDK，可 mock）
- `pom.xml`（新增 `tencentcloud-sdk-java-cls`，理由见 D1）
- `src/main/resources/application.yml`（第 46-50 行 MCP 配置处理、第 76-78 行 `cls.*` 配置扩展）、`application.yml.example`

**具体改动**

1. **依赖与凭证**：引入 `tencentcloud-sdk-java-cls`（版本以官方最新稳定为准，与 `okhttp3` 无冲突）。凭证遵守 P0 5.1"敏感值无默认值外置"：
   ```yaml
   cls:
     mock-enabled: false            # 现有键，语义见第 3 点
     region: ${CLS_REGION:ap-guangzhou}
     secret-id: ${TENCENTCLOUD_SECRET_ID}      # 无默认值，缺失即启动失败
     secret-key: ${TENCENTCLOUD_SECRET_KEY}    # 无默认值
     topic-mapping:                 # 逻辑主题 → CLS TopicId（原 getAvailableLogTopics 硬编码项配置化）
       system-metrics: ${CLS_TOPIC_SYSTEM_METRICS:}
       application-logs: ${CLS_TOPIC_APPLICATION_LOGS:}
       database-slow-query: ${CLS_TOPIC_DB_SLOW_QUERY:}
       system-events: ${CLS_TOPIC_SYSTEM_EVENTS:}
   ```

2. **`queryLogs` 真实分支实现**（替换第 179-182 行占位错误）：
   ```java
   // 新增注入 ClsLogClient（构造注入，便于单测 mock）
   } else {
       List<LogEntry> entries = clsLogClient.searchLogs(region, resolveTopicId(logTopic), safeQuery, actualLimit);
       logEntries = entries.stream().map(LogEntry::fromClsLog).toList();
   }
   ```
   `ClsLogClient` 内部封装 SDK 的 `SearchLogRequest`：`TopicId`、`Query`（Lucene 语法，与现有工具描述一致）、时间范围（默认近 24h，配置 `cls.search-hours`）、`Limit`（对齐 `actualLimit` 1~100）、`Region`。结果 `LogEntry` 复用现有内部类（第 609-627 行）字段（timestamp/level/service/instance/message/metrics），映射策略：CLS 原始字段 `__CONTENT__`、`__TIMESTAMP__` 以及业务字段（`level`/`service`/`instance` 等按日志字段名匹配，缺失置空）。

3. **`cls.mock-enabled` 语义 + profile 策略**（正式化，消除当前"mock/真实二选一靠 MCP 注入"的隐式约定）：
   - `cls.mock-enabled=true`：走 Mock（本地开发/演示/无凭证环境）；`=false`：走 `ClsLogClient` 真实查询。
   - **profile 策略**：`application.yml` 默认 `false`；新增 `application-mock.yml` 将 `cls.mock-enabled: true` 与 `prometheus.mock-enabled: true` 一并打开，供演示/CI。生产环境不激活 mock profile。
   - **清理 `ChatService` 的隐式二选一**：删除第 41-42 行 `@Autowired(required=false)` 的"可选注入"注释语义与第 123-131 行 `buildMethodToolsArray()` 里"真实模式不含 QueryLogsTools"的分支——`QueryLogsTools` 恒注册为工具（其真实分支已可用），工具数组恒为 4 项；第 97 行提示词"请调用腾讯云mcp服务"改为"请调用 queryLogs 工具查询腾讯云 CLS 日志"。

4. **假数据归档/删除**：删除 `buildMockLogs`、`buildSystemMetricsLogs`、`buildApplicationLogs`、`buildDatabaseSlowQueryLogs`、`buildSystemEventsLogs`、`buildGenericLogs` 六个方法（约 210-587 行）。Mock 模式改为由 `ClsLogClient` 提供的一个**极简固定样本**（如每个逻辑主题 2-3 条示意日志，约 30 行），或直接让 mock 分支返回"Mock 模式已启用，未接真实数据源"的明确提示——推荐**保留极简固定样本**（演示需要能看到日志效果），但不再按 query 关键词精细伪造。

5. **`getAvailableLogTopics` 配置化**（第 57-132 行）：topic 列表与 `relatedAlerts`、`exampleQueries` 改为从 `cls.topic-mapping` + 一个说明性配置读取；`availableRegions`/`defaultRegion` 改读 `cls.region` 相关配置。无法配置化（如 `exampleQueries`）的部分可保留静态，但 topic 名/ID 映射必须配置化，否则换环境要改代码。

**验收标准**
- 配置真实 `TENCENTCLOUD_SECRET_ID/KEY` + 有效 `cls.topic-mapping` 后，Agent 提问"查一下 payment-service 最近的 ERROR 日志"能返回真实 CLS 日志（非 Mock）；
- 未配置 `TENCENTCLOUD_SECRET_ID/KEY` 且 `cls.mock-enabled=false` 启动 → 启动失败并报缺失键名（P0 fail-fast 语义）；`cls.mock-enabled=true` 时无凭证可启动并返回精简样本；
- 全工程 grep `buildSystemMetricsLogs|buildApplicationLogs|buildDatabaseSlowQueryLogs|buildSystemEventsLogs` 命中为 0（假数据已删）；`buildMockLogs` 仅剩精简样本；
- `ChatService.buildMethodToolsArray()` 不再有"真实模式排除 QueryLogsTools"的分支，工具数组恒含 `queryLogs`；
- `getAvailableLogTopics` 的 topic 名/ID 从配置读取，换 `CLS_TOPIC_*` 环境变量后无需改代码；
- 错误路径（SDK 异常、超时、凭证错）返回合法 JSON（P0 T8 约定），异常经 P1 `GlobalExceptionHandler` 兜底、日志脱敏。

---

### T2｜P3-高｜告警接入真实 Prometheus

**目标**：告警真实可查；凭证/地址外置；评审并修正"相同 alertname 只保留第一个"的去重策略；删除硬编码假告警。

**涉及文件**
- `src/main/java/org/example/agent/tool/QueryMetricsTools.java`（第 61-120 行 `queryPrometheusAlerts`、第 79-92 行去重逻辑、第 131-171 行 `buildMockAlerts`、第 176-193 行 `fetchPrometheusAlerts`）
- `src/main/resources/application.yml`（第 70-74 行 `prometheus.*`）、`application.yml.example`

**具体改动**

1. **地址与凭证环境变量化**（`fetchPrometheusAlerts` 已实现 `GET {base-url}/api/v1/alerts` 真实调用，第 176-193 行，本期主要补配置与鉴权）：
   ```yaml
   prometheus:
     base-url: ${PROMETHEUS_BASE_URL}          # 去掉 localhost 占位；无默认值，缺失即启动失败
     timeout: 10
     mock-enabled: false
     username: ${PROMETHEUS_USERNAME:}          # 可选 basic auth（空则不带）
     password: ${PROMETHEUS_PASSWORD:}          # 敏感，无默认值外置
   ```
   `fetchPrometheusAlerts` 增加：`base-url` 末尾去重 `/` 拼接 `/api/v1/alerts`；若 `username/password` 非空，`Request.Builder` 增加 `Authorization: Basic base64(user:pass)` 头；凭证不入日志。

2. **去重策略评审（第 79-92 行）**：当前"相同 alertname 只保留第一个"会丢失同一告警在多个实例/多组标签下的信息（如 `HighCPUUsage` 在 payment-service 与 order-service 同时触发时只显示一条）。改为**按 alertname 聚合**：
   ```java
   // 保留所有 firing 告警，按 alertname 聚合为「数量 + 样例 + 去重后的受影响服务/实例列表」
   Map<String, List<PrometheusAlert>> grouped = result.getData().getAlerts().stream()
       .collect(Collectors.groupingBy(a -> a.getLabels().get("alertname")));
   // SimplifiedAlert 增加 firingCount 字段（同一 alertname 触发条数）
   // description 取首条，另附「共 N 条 firing，涉及实例: a,b,c」的聚合说明
   ```
   若担心 token 占用，设置告警条数上限（如最多返回 50 条，超出在 message 中提示截断）。原第 79-92 行"只保留第一个"逻辑删除。

3. **删除硬编码假告警**：`buildMockAlerts()`（第 131-171 行）删除或收敛为与 T1 一致的极简固定样本（`prometheus.mock-enabled=true` 时返回 1-2 条示意告警）。

4. **`prometheus.mock-enabled` 语义**：与 T1 第 3 点对齐，纳入 `application-mock.yml`。

**验收标准**
- 配置真实 `PROMETHEUS_BASE_URL` 后，Agent 提问"当前有哪些告警"返回真实 firing 告警；
- 未配置 `PROMETHEUS_BASE_URL` 且非 mock 启动 → 启动失败报缺失键名；
- 同一 `alertname` 多实例 firing 时返回聚合信息（含 firingCount），不再只保留第一条；
- `buildMockAlerts` 硬编码 3 条已删（grep 验证），mock 仅剩精简样本；
- 带 basic auth 的 Prometheus 环境可用；凭证不出现在日志/响应中。

---

### T3｜P3-高｜RagService 接入（暴露 /api/rag + reasoning 流式 + 模型配置统一）

**目标**：对死代码 `RagService` 做出"接入或删除"决策并落地（D2 选"接入"）；产出确定性 RAG 入口供前端与 T7 评测复用。

**涉及文件**
- `src/main/java/org/example/service/RagService.java`（第 42 行模型默认值、第 141-221 行 `generateAnswerStream`、第 187 行 `reasoningContent`、第 219 行 `onComplete`、第 226-232 行 `StreamCallback`）
- 新增（允许）：`src/main/java/org/example/controller/RagController.java`
- `src/main/resources/application.yml`（第 65-68 行 `rag.*`）

**具体改动**

1. **新增 `/api/rag`（SSE 流式）**（挂 `/api/**` 自动纳入 P0 `X-API-Key` 鉴权；响应沿用 `ChatController.SseMessage` 的 `content/error/done` 事件类型，另加 `search`/`reasoning` 两类，见下）：
   ```java
   @PostMapping(value = "/api/rag", produces = "text/event-stream;charset=UTF-8")
   public SseEmitter rag(@RequestBody RagRequest request) {
       // 复用 P2 有界线程池 sseExecutor（而非 ChatController 第 52 行的 newCachedThreadPool）
       // ragService.queryStream(request.getQuestion(), request.getHistory(), new RagStreamCallback(emitter));
   }
   ```
   事件协议（扩展 `ChatController.SseMessage`，保持 `type/data` 形状）：
   - `{"type":"search","data":<检索结果 JSON 数组>}` —— 检索召回文档（对应 `StreamCallback.onSearchResults`）；
   - `{"type":"reasoning","data":"<思考内容增量>"}` —— reasoning 流式（对应 `onReasoningChunk`）；
   - `{"type":"content","data":"<答案增量>"}` —— 最终答案流式；
   - `{"type":"error"}` / `{"type":"done"}` —— 结束。错误走 P0 统一错误体形状（脱敏）。

2. **补齐 reasoning 流式**（第 187 行 `reasoningContent` 从未写入、第 228 行 `onReasoningChunk` 从未调用）：
   - DashScope thinking 模型（`qwen3-*thinking*`）返回的 message 中含 `reasoning_content` 字段（最终以 `dashscope-sdk-java` 2.17.0 的 `GenerationResult` message 实际字段为准，通常为 `getReasoningContent()`）；
   - 在 `generateAnswerStream` 的 `blockingForEach`（第 192-215 行）中：增量 `reasoning_content` 追加到 `reasoningContent` 并回调 `onReasoningChunk(chunk)`；增量 `content` 追加到 `finalContent` 并回调 `onContentChunk(chunk)`（现有逻辑）；第 219 行 `onComplete(finalContent.toString(), reasoningContent.toString())` 传实值。

3. **统一模型配置**（消除第 42 行默认值与 yml 第 68 行不一致）：`rag.model` 以 yml 为唯一权威，代码默认值删除（改 `@Value("${rag.model}")`，缺失即启动失败，符合 P0 fail-fast）。若确需 reasoning 展示，`rag.model` 显式配一个 thinking 模型（如 `qwen3-30b-a3b-thinking-2507`）；若配非 thinking 模型（如 `qwen3-max`），`reasoning` 事件直接不发，前端按无 reasoning 渲染。`RagService.init()` 第 56 行日志保持脱敏（只打 model/topK）。

4. **与 Agent 路径的关系**：`RagService` 是"纯 RAG"链路（检索 + 生成，不经 ReactAgent 工具编排），与 `ChatController` 的 agent 路径（经 `InternalDocsTools` 工具做检索）并存不冲突。`RagService` 直接复用 P2 的 `VectorRepository.search`（而非直接调 `VectorSearchService`，若 P2 已抽取 Repository）——若 P2 尚未落地 Repository，本期先直接复用 `VectorSearchService.searchSimilarDocuments`（第 42-94 行），不阻塞。

**验收标准**
- `POST /api/rag`（带 `X-API-Key`）流式返回 search → reasoning（模型支持时）→ content → done，前端可完整渲染；
- 不带 Key 访问 `/api/rag` → HTTP 401 + P0 统一错误体；
- 配 thinking 模型时 `reasoning` 事件有内容且与最终答案可区分；配 `qwen3-max` 时无 `reasoning` 事件但 `content` 正常；
- 检索结果（search 事件）与 `VectorSearchService.searchSimilarDocuments` 一致；
- `rag.model` 无默认值，未配置启动失败；yml 与代码不再有两处不一致的模型名；
- `RagService` 单测覆盖：空检索结果（第 87-91 行"未找到相关文档"分支）、reasoning/content 分块回调顺序。

---

### T4｜P3-高｜知识库文档管理接口（列表 / 删除 / 重建）

**目标**：复用 P2 的 `VectorRepository` 与索引任务 API，提供文档维度的管理接口；删除文档同步删向量（走 P0 白名单 + 转义约定）。

**涉及文件**
- 新增（允许）：`src/main/java/org/example/controller/DocumentController.java`、`src/main/java/org/example/dto/DocumentSummary.java`
- 修改：`src/main/java/org/example/repository/VectorRepository.java`（P2 产物，新增 `listDocuments()` 能力，见下）
- 复用：`src/main/java/org/example/task/IndexTaskManager.java`（P2 产物，提交重建任务）

**具体改动**

1. **`VectorRepository` 扩展 `listDocuments()`**（P2 第 5.3 节约定 Repository 能力面 `insertBatch/deleteBySource/search/loadCollectionOnce`，本期在此之上新增只读聚合能力，Controller 不得再拼 gRPC）：
   ```java
   // 返回按 source 聚合的文档摘要：source（文件名）、chunkCount、最近索引时间
   public List<DocumentSummary> listDocuments() { ... }
   ```
   依赖 P2 T6 的 `_source` 字段化（标量 `source` 字段 + 标量索引），用 Milvus `QueryParam` 按 `source` 分组统计（或 `count(*)` 聚合）；若 P2 尚未落地 `source` 标量字段，退化为遍历查询（临时），文档中标注该依赖。`DocumentSummary` 至少含 `source`（脱敏文件名）、`chunkCount`、`lastIndexedAt`（来自 metadata 的 `indexedAt`，需在 P2 写入链路补充该字段，或从 `argus:index:task:*` 取最近一次 SUCCESS 时间）。

2. **文档列表接口**：
   ```
   GET /api/documents          → {"code":0,"message":"success","data":[{"source":"磁盘告警处置.md","chunkCount":42,"lastIndexedAt":"...","indexStatus":"SUCCESS"}]}
   ```
   索引状态来源：合并 `VectorRepository.listDocuments()`（已入库文档）与 P2 任务 API `GET /api/index/tasks`（进行中/失败任务），前端据此渲染"已索引/索引中/索引失败"徽标。仅返回**已索引文档**（存在向量的 source），不在 `uploads/` 但无向量的文件在管理页另列（可选，见 T5）。

3. **删除文档（同步删向量）**：
   ```
   DELETE /api/documents/{fileName}   → 按 fileName 精确删除该文档全部向量
   ```
   - 入参 `fileName` 必须匹配 P0 `SAFE_FILENAME_REGEX`（复用 P1 `org.example.util.FilenameSanitizer`），不匹配直接 400；
   - 内部构造完整 `source`（`uploadPath + fileName`，统一正斜杠），调 `VectorRepository.deleteBySource(source)`——表达式拼接走 P0 `escapeExprString()`；
   - 可选参数 `?deleteFile=true` 同步删除 `uploads/` 本地文件（默认只删向量）；删除动作写审计（T8）。

4. **重建文档**：复用 P2 索引任务 API，新增一个便捷入口（或前端直接调 `POST /api/index/tasks`）：
   ```
   POST /api/documents/{fileName}/reindex   → 提交该文件的索引任务，返回 {"data":{"taskId":"..."}}
   ```
   内部等价于 `IndexTaskManager.submitFile(uploadPath + fileName)`（P2 T3 产物），同样过 `SAFE_FILENAME_REGEX`。

**验收标准**
- `GET /api/documents` 返回与 Milvus 实际一致的去重文档列表（chunk 数正确）；
- `DELETE /api/documents/{fileName}` 后，`VectorRepository.listDocuments()` 中该文档消失、Milvus 中 `source` 精确匹配的 chunk 归零，**其他文档 chunk 数不变**（验证转义不误删）；
- `DELETE /api/documents/../../etc/passwd`、`DELETE /api/documents/a".md` 等非法名 → HTTP 400，不触发删除；
- 三个接口无 `X-API-Key` 访问 → 401；响应体均走 P0 统一形状；
- 删除/重建动作进入 T8 审计日志。

---

### T5｜P3-中｜知识库管理前端页面

**目标**：在现有聊天界面新增"知识库管理"入口与页面，可视化文档列表、索引状态、删除/重建操作。

**涉及文件**
- `src/main/resources/static/index.html`（侧边栏第 17-40 行区域加"知识库管理"入口）
- `src/main/resources/static/app.js`（新增管理页逻辑，复用 T6 的请求封装）
- `src/main/resources/static/styles.css`（管理页样式）

**具体改动**

1. 侧边栏（`index.html` 第 23-37 行 "新建对话"按钮与 "近期对话" 列表之间）加"知识库管理"按钮，点击切换到管理视图（或独立 `admin.html` 页面，推荐独立页面 + 共用 `vendor/` 静态资源，避免与聊天状态耦合）。
2. 管理页功能：
   - 文档列表：`GET /api/documents` + `GET /api/index/tasks` 合并，每行显示文件名、chunk 数、索引状态徽标（已索引 / 索引中 / 失败 / 待索引）；
   - 删除按钮：二次确认弹窗 → `DELETE /api/documents/{fileName}` → 刷新列表；
   - 重建按钮：`POST /api/documents/{fileName}/reindex`（或 `POST /api/index/tasks`）→ 轮询 `GET /api/index/tasks/{taskId}`（复用 P2 轮询逻辑）更新徽标；
   - 上传入口复用现有上传，上传后自动刷新列表与状态。
3. 所有请求带 `X-API-Key`（T6 统一封装）。

**验收标准**
- 管理页能列出真实文档、显示正确 chunk 数与状态徽标；删除后列表即时刷新且向量同步删除；重建任务完成后徽标变"已索引"；
- 非法/越权操作（无 Key、非法文件名）在前端有明确提示，不产生误删。

---

### T6｜P3-中｜前端工程化与交互体验

**目标**：消除 API 地址硬编码；输入升级 textarea；SSE 支持错误态/重试/停止；上传带进度；会话对接 Redis。

**涉及文件**
- `src/main/resources/static/app.js`（第 4 行 `apiBaseUrl`、第 167-174 行 Enter 处理、第 357-374 行 localStorage、第 662-802 行 `sendStreamMessage`、第 1035-1091 行 `uploadFile`、第 1103-1237 行 `sendAIOpsRequest`）
- `src/main/resources/static/index.html`（第 62 行 input → textarea、第 106 行发送按钮改为发送/停止切换）
- `src/main/resources/static/styles.css`（textarea、字数提示、进度条样式）

**具体改动**

1. **API 地址配置注入（D4 决策）**：第 4 行改为相对路径 `this.apiBaseUrl = '/api'`（同源部署，消除跨域与硬编码）；运行时开关（如 `cls.mock-enabled` 状态、版本号）通过可选 `GET /api/config` 下发，页面启动时 fetch 一次写入 `this.config`。`X-API-Key` 沿用 P0 T6 约定（`sessionStorage`，首次提示输入）。

2. **textarea 交互**：`index.html` 第 62 行 `<input type="text" ... maxlength="1000">` 改为 `<textarea id="messageInput" rows="1" maxlength="1000">` + 字数提示元素（`0/1000`）。`app.js` 第 167-174 行 `keypress` 改 `keydown`：`Enter` 且无 `shiftKey` 发送（`preventDefault`）；`Shift+Enter` 换行；`input` 事件更新字数提示与自适应高度。

3. **错误态 + 重试 + 停止生成**：
   - `sendStreamMessage`（662-802）/`sendAIOpsRequest`（1103-1237）改用 `AbortController`，`fetch` 传入 `signal`；`isStreaming` 期间发送按钮（`index.html` 第 106 行 `#sendButton`）切换为"停止"按钮，点击 `controller.abort()` 中断流；
   - SSE 收到 `error`（第 758-764 行）或网络异常（第 799-801 行）时，在助手消息下方渲染"重试"按钮，点击用上一条用户消息重发（不复用已中断的流）；
   - `abort`/错误态不把半截内容误存进 `currentChatHistory`（第 956-968 行 `handleStreamComplete` 仅在 `done`/正常结束时入史）。

4. **上传进度条**：`uploadFile`（第 1035-1091 行）由 `fetch` 改为 `XMLHttpRequest`，监听 `xhr.upload.onprogress` 更新进度条（`loaded/total`），完成后走统一响应体解析（沿用第 1069-1077 行逻辑）。保留前端文件类型（第 1028-1032 行 `validateFileType`）与大小校验（第 1043-1047 行）。

5. **会话对接 P2 Redis**：第 6、267、470 行前端自造 `sessionId` 改为以 P2 T4 后端回传为准——流式首帧 `{"type":"session","data":"<id>"}`（P2 已定）覆盖本地 id；非流式读响应 `sessionId` 字段。第 357-374 行 localStorage 历史改为"后端为权威 + 本地仅缓存"：新增会话列表接口（挂 `/api/**`）`GET /api/chat/sessions`、`DELETE /api/chat/sessions/{sessionId}`（复用 P2 `SessionStore`），前端历史列表从后端拉取，本地 `chatHistories` 仅作离线缓存、启动时以后端为准合并。

6. **原生 ES Modules 拆分（可选，不引构建工具）**：`app.js` 约 1549 行，可按职责拆 `api.js`（请求/鉴权/SSE 解析）、`chat.js`、`markdown.js`、`upload.js`，用 `<script type="module">` 组织；仅作可维护性拆分，不改变运行行为（D3 决策）。

**验收标准**
- 页面不出现 `http://localhost:9900` 硬编码；同源部署 + 相对路径下全功能可用，跨域问题消失；
- textarea 支持 Enter 发送 / Shift+Enter 换行 / 字数提示 / 自适应高度；超 1000 字无法输入；
- 流式过程中出现"停止"按钮，点击后流立即中断、无残留半截内容；SSE 错误后显示"重试"，重试能完整重新生成；
- 上传大文件有进度条且不阻塞其他交互；
- 会话历史从后端读取（重启后历史仍在，Redis 外置生效）；前端不再以 localStorage 为唯一权威。

---

### T7｜P3-中｜RAG 质量评估体系

**目标**：建立可重复执行的评测脚本，量化检索召回率@k 与答案相关性；坏例回流形成质量闭环。

**涉及文件**
- 新增（允许）：`scripts/eval/README.md`、`scripts/eval/eval_retrieval.py`、`scripts/eval/eval_answer.py`、`scripts/eval/questions.jsonl`、`scripts/eval/badcases/`
- 复用：`POST /api/rag`（T3）作为确定性评测入口（不经 Agent 工具编排，避免噪声）

**具体改动**

1. **评测问题集 `questions.jsonl`**（每行一条）：
   ```json
   {"id":"q001","question":"磁盘使用率告警如何处置？","relevant_sources":["disk_high_usage.md"],"gold_answer":"..."}
   ```
   首期由 `aiops-docs/`（根目录 `cpu_high_usage.md` 等 5 篇）人工构造 20-30 条，覆盖：单文档直答、跨文档综合、边界（文档未覆盖的问题）。

2. **检索召回率@k**（`eval_retrieval.py`）：对每条 question 调 `/api/rag` 的 `search` 事件（或直接调 `VectorSearchService.searchSimilarDocuments`），取 top-k（默认 k=3）的 `source`，与 `relevant_sources` 比对：
   ```
   recall@k = (命中相关 source 的条数) / (该 question 的相关 source 总数)
   报告：平均 recall@1 / recall@3、按文档分组的召回分布
   ```

3. **答案相关性**（`eval_answer.py`）：
   - 人工标注：`questions.jsonl` 增加 `score`（1-5 分）字段，人工对最终答案打分（相关性/准确性/完整性）；
   - LLM-as-judge（可选，默认关闭）：用一个独立模型（配 `rag.judge.model`）按 rubric 打分，与人工标注计算一致性（Cohen's kappa），一致性不足 0.6 时以人工为准；
   - 报告：平均分、低分（<3 分）case 列表。

4. **坏例回流**：低分/漏召回的 question 与检索到的错误 source 写入 `scripts/eval/badcases/`（带时间戳、链路快照），作为后续"补文档/调分片/换检索"的输入；`eval_*` 脚本支持 `--badcase` 只跑坏例集回归。

5. **脚本栈**：Python 3（标准库 `urllib`/`json`，不额外装依赖；如需 HTTP 便捷性可用 `requests` 并在 README 声明）。落点 `scripts/eval/`（不进入 Maven 构建）。

**验收标准**
- `python scripts/eval/eval_retrieval.py` 与 `eval_answer.py` 可重复执行，输出结构化报告（JSON + 可读摘要）；
- 首期评测集 ≥ 20 条；recall@3 基线记录在案（见第 6 节"通过标准"）；
- 坏例集有明确回流入口，且重跑坏例集可复现问题。

---

### T8｜P3-低｜轻量多用户与审计

**目标**：给出用户体系取舍（D6）；落地结构化审计日志，覆盖问答与知识库变更。

**涉及文件**
- `src/main/java/org/example/config/ApiKeyAuthFilter.java`（P0 产物，扩展"Key → 操作者名"映射）
- 新增（允许）：`src/main/java/org/example/common/audit/AuditLogger.java`、`src/main/java/org/example/common/audit/AuditEvent.java`
- 修改：`ChatController.java`（问答入口记审计）、`DocumentController.java`（T4 产物，删除/重建记审计）、`FileUploadController.java`（上传记审计）

**具体改动**

1. **多用户取舍（D6）**：不引入 Spring Security / 登录 / SSO。将 `security.api-key`（单值）扩展为可选的多 Key 映射，实现"一个 Key = 一个操作者"的轻量归属：
   ```yaml
   security:
     api-key: ${APP_API_KEY}                 # 保留单 Key（向后兼容）
     api-key-map: ${APP_API_KEY_MAP:}        # 可选，形如 key1:operator1,key2:operator2
   ```
   `ApiKeyAuthFilter` 鉴权通过后把命中的操作者名写入请求属性（如 `request.setAttribute("argus.operator", name)`，无映射时用 `anonymous`/`default`），供审计与日志使用。**不提供**权限分级、密码登录、会话登录态。

2. **审计日志**（结构化，D5 决策，不落数据库）：
   ```java
   // AuditEvent 字段：ts、traceId（P1 MDC）、operator、action、target、result、detail（脱敏）
   AuditLogger.audit("DELETE_DOCUMENT", "磁盘告警处置.md", "SUCCESS", null);
   ```
   - 落点：独立 SLF4J logger（`loggerName="AUDIT"`），经 P1 logback 配置输出到单独文件（如 `logs/audit.log`），JSON 行格式（Jackson 序列化，遵守 P0 5.3）；
   - 覆盖动作：`ASK`（问答，记录 question 脱敏摘要 + sessionId）、`UPLOAD`（文件名）、`DELETE_DOCUMENT`（文件名）、`REINDEX`（文件名 + taskId）、`QUERY_LOGS`/`QUERY_ALERTS`（可选，工具调用侧记录，避免高频刷屏）；
   - 内容脱敏：question 全文仅存哈希 + 前 50 字摘要（防敏感信息落审计），detail 不含密钥/向量/完整日志正文。

3. **指标**：审计事件计数挂 P1 Micrometer（`argus.audit.events`，按 action 打 tag）。

**验收标准**
- 问答、上传、删除文档、重建四类动作均产生结构化审计 JSON 行，含 operator、traceId、时间、结果；
- 单 Key 模式行为与 P0 完全一致（无回归）；多 Key 模式下不同 Key 的 operator 正确区分；
- 审计日志可被 `grep` 定位到"谁在何时删了哪个文档"；日志中无密钥/向量/question 全文；
- 审计文件按 P1 logback 滚动，无无限增长。

---

## 4. 关键技术决策

### D1 日志真实接入：腾讯云 CLS MCP 接入 vs CLS SDK 直连
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. MCP 接入（现状设计方向） | 零新增 SDK 依赖；`spring-ai-starter-mcp-client-webflux` 已在 `pom.xml` 第 143-146 行，`application.yml` 第 46-50 行已有 MCP SSE 配置 | 工具 schema 不可控（`queryLogs` 的精心设计的工具描述/topic 说明失效，Agent 面对未知工具名）；依赖腾讯云托管 MCP 网关（`https://mcp-api.tencent-cloud.com`）作为外部单点，内网不可达/难离线测试；当前 SSE 端点凭证是打码占位，需在服务侧重建；`QueryLogsTools` 与 MCP 工具并存导致"日志查询两套工具"冲突（`ChatService` 第 123-131 行的二选一逻辑当前因 `QueryLogsTools` 恒注册而失效） |
| B. CLS SDK 直连（**选定**） | 工具 schema 可控（保留现有 `queryLogs` 的 topic/curated query 描述）；凭证用 `TENCENTCLOUD_SECRET_ID/KEY` 环境变量，天然符合 P0 5.1"数据源凭证无默认值外置"；可单测（mock `ClsLogClient`）、可离线开发；消除 MCP 外部依赖与双工具冲突 | 新增 1 个 SDK 依赖（`tencentcloud-sdk-java-cls`，官方标准，无重型传递）；需自行处理签名（SDK 内置，无成本） |

**选 B**，并同步清理 MCP 配置（`application.yml` 第 39-50 行 MCP `sse.connections` 移除或显式注释，`ChatService` 第 97 行提示词与第 123-131 行二选一逻辑一并修正）。理由：产品化要求"工具行为可控 + 凭证外置 + 可测试"，SDK 直连三条全满足；MCP 方案的工具 schema 不可控与外部单点，与"面向 OnCall 的确定性问答"目标冲突。

### D2 RagService：接入 vs 删除
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 接入（暴露 `/api/rag`）（**选定**） | ① 为 T7 评测提供**确定性 RAG 入口**（不经 ReactAgent 工具编排，避免 agent 决策噪声污染评测）；② reasoning 透明化是 OnCall 场景可解释性卖点；③ 代码已 ~90% 完成（检索、prompt、流式骨架都在），接入成本低；④ 轻量纯问答路径，省去工具编排开销 | 需新增 1 个 Controller + 补齐 reasoning 提取（约 50 行）；与 Agent 路径并存需写清职责边界 |
| B. 删除 | 消除死代码，仓库更干净 | 评测仍需另造"确定性 RAG"入口（绕不开）；浪费已实现逻辑；丧失 reasoning 展示能力 |

**选 A**，职责边界：`/api/rag` = 纯 RAG（检索 + 生成，可展示 reasoning），`/api/chat*` = Agent（含工具调用）。二者共用检索底层（P2 `VectorRepository.search`）。

### D3 前端构建工具：保持原生 JS vs 引 Vite
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 保持原生 JS（ES Modules 拆分）（**选定**） | 零 node 工具链与构建产物管理；静态页规模小（`app.js` + `index.html` + `styles.css` + `vendor/`），无组件/依赖树；Spring Boot 直接 serve `static/`，无需构建-拷贝-路径配置 | 无 TS 类型、无热更新、无 tree-shaking（当前规模收益可忽略） |
| B. 引 Vite | 组件化、TS、HMR、产物优化 | 引入 node/npm 工具链、构建产物与资源路径改造（与 Spring Boot 静态资源托管衔接）；当前单页规模下收益低，属"为工具而工具" |

**选 A**：仅按职责拆 ES Modules（`api.js`/`chat.js`/`markdown.js`/`upload.js`）提升可维护性，不引构建工具。若后续前端复杂度显著上升（多页面组件化、TS、依赖树），再评估迁移 Vite（届时静态资源改由 Vite 输出目录挂到 `static/`，接口契约不变）。

### D4 API 地址配置注入：window.__APP_CONFIG__ vs /api/config vs 相对路径
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 相对路径 `/api`（**选定，基础**）+ 可选 `/api/config` 下发运行时开关 | 同源部署彻底消除跨域与硬编码（P0 T6 已定方向）；`/api/config` 由后端控制运行时态（mock 开关、版本、是否启用 reasoning），页面加载时 fetch 一次 | `/api/config` 属 `/api/**` 需鉴权（可设为免鉴权只读项或带 Key 拉取） |
| B. `window.__APP_CONFIG__` 模板注入 | 首屏无额外请求 | 需引入 Thymeleaf 或静态资源渲染（改造大），且配置在构建/部署期固化，不如运行期下发灵活 |
| C. 继续硬编码 | 零改动 | 跨环境必踩坑（当前 `http://localhost:9900` 直接阻断非本机访问），不可接受 |

**选 A**：`apiBaseUrl='/api'` 为强制项；`/api/config` 为可选增强（放行白名单只读，返回非敏感开关）。

### D5 审计存储：结构化日志 vs 关系型数据库 vs Redis
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 结构化 JSON 日志（独立 AUDIT logger → 单独文件）（**选定**） | 零新依赖，符合现有栈（SLF4J + P1 logback）；运维侧已有日志采集/归档习惯，`grep`/`ELK` 直接可用；与 P0 5.3"日志脱敏"约定天然衔接 | 查询聚合不如 SQL 方便；长期留存放日志归档（可接受，审计量级小） |
| B. 关系型数据库（H2/SQLite/MySQL） | 强查询/聚合 | 引入新存储组件与迁移/Schema 管理，超出现有"Milvus + Redis"栈；单租户低量级审计用数据库属过度设计 |
| C. Redis（List/Stream） | 复用已有 Redis | 内存型、TTL 会丢审计（审计要求长期留痕）；非权威持久化 |

**选 A**，保留演进空间：若未来需要"审计检索/导出报表"，再将 AUDIT 文件导入专用表（接口不变，只换输出 appender）。

### D6 用户体系：完整登录/RBAC vs 轻量多 Key 归属 vs 维持单 Key
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 完整 Spring Security + 登录 + RBAC | 强身份、权限分级、会话态 | 引入 Security 过滤链 + 用户存储 + 登录页/会话 cookie，与现有 SSE、静态资源放行的改造面大；内部单租户运维工具收益低 |
| B. 轻量多 Key → 操作者名（**选定**） | 几乎零改造（扩展 P0 `ApiKeyAuthFilter`）；实现"审计可归属到人"这一核心诉求；`X-API-Key` Header 约定不变，前端无需改 | 仍是"共享口令"模型，无强身份/防重放/权限分级（已在 P0 D2 显式接受） |
| C. 维持单 Key | 零改动 | 审计无法归属到人，多用户场景无法区分操作者 |

**选 B**：P3 只解决"谁干的"（归属），不解决"谁能干什么"（授权）。如需权限分级，作为独立演进项另立方案，不影响本阶段。

---

## 5. 与其它阶段的依赖关系

### 5.1 沿用 P0（`docs/plans/P0-security.md` 第 5 节）约定情况

| P0 约定 | P3 沿用方式 |
|---|---|
| 敏感配置无默认值外置 | 新增数据源凭证照此办理：`TENCENTCLOUD_SECRET_ID/KEY`（T1）、`PROMETHEUS_PASSWORD`（T2，敏感）、`PROMETHEUS_BASE_URL`（T2，非敏感但无默认值）均 `${VAR}` 无默认值、缺失 fail-fast；`cls.region`/`prometheus.timeout` 等非敏感项可留默认 |
| `X-API-Key` 鉴权，`/api/**`、`/milvus/**` 强制，静态资源免鉴权 | 新增 `/api/rag`、`/api/documents*`、`/api/chat/sessions*` 全部挂 `/api/**` 自动纳入；`/api/config` 若免鉴权须显式加白（且只下发非敏感开关） |
| 统一响应体：成功 `{"code":0,...}`；错误 HTTP 4xx/5xx + `{"code":<HTTP码>,"message":<脱敏文本>,"data":null}` | T4 文档管理接口、T3 `/api/rag` 错误路径、T6 新增会话接口全部遵循；SSE 事件体继续沿用 `{type,data}` 扩展 `search/reasoning/session` 类型 |
| JSON 一律 Jackson，禁止 `String.format` 拼 JSON | `ClsLogClient`/`fetchPrometheusAlerts` 的响应解析、审计 JSON 行、评测脚本输出均 Jackson；修复遗留（`QueryLogsTools` 第 599 行、`QueryMetricsTools` 第 231 行已在 P0 T8 治理，T1/T2 同步落地） |
| 日志脱敏（不打印密钥/向量/凭证 URL） | CLS/Prometheus 凭证、审计 question 全文、reasoning/日志正文均不得裸打印；审计 detail 只存哈希+摘要 |
| `SAFE_FILENAME_REGEX` + 三重校验 + `escapeExprString()` | T4 删除/重建的 `fileName` 入参过 `FilenameSanitizer`（P1 产物）；`VectorRepository.deleteBySource` 继续 `escapeExprString()` 双保险 |

### 5.2 沿用 P1（工程化底座）约定情况

| P1 约定 | P3 沿用方式 |
|---|---|
| 指标前缀 `argus.` | 新增指标同前缀：`argus.rag.calls/latency`（/api/rag）、`argus.cls.calls/latency`、`argus.prometheus.calls/latency`、`argus.audit.events`（按 action 打 tag） |
| MDC traceId | CLS/Prometheus 工具调用、审计日志、`/api/rag` 全链路透传 traceId，问题可追踪 |
| `org.example.common` / `org.example.util.FilenameSanitizer` | 审计类落 `org.example.common.audit`；文件名净化复用 `FilenameSanitizer`（T4 删除/重建） |
| `BizException` + `GlobalExceptionHandler` | CLS/Prometheus 调用失败、凭证缺失、文件名非法统一走该体系，响应体形状与 P0 5.2 一致 |
| JUnit 5 + Mockito 测试底座 | `ClsLogClient`（mock SDK）、`RagService`（reasoning/content 回调顺序）、`VectorRepository.listDocuments`（打桩 MilvusServiceClient）单测基于该底座 |

### 5.3 沿用 P2（`docs/plans/P2-performance.md` 第 5 节）约定情况

| P2 约定 | P3 沿用方式 |
|---|---|
| 索引任务 API（`POST /api/index/tasks`、`GET /api/index/tasks/{taskId}`、`GET /api/index/tasks`，状态 PENDING/RUNNING/SUCCESS/FAILED） | T5 管理页状态徽标直接对接；T4 `POST /api/documents/{fileName}/reindex` 复用 `IndexTaskManager.submitFile`，不改任务 API 语义 |
| `VectorRepository`（`insertBatch/deleteBySource/search/loadCollectionOnce`） | T4 扩展只读能力 `listDocuments()`；删除走 `deleteBySource`；检索（T3）走 `search`；Controller 不再拼 gRPC |
| Redis key 前缀 `argus:` | T6 会话列表接口复用 `argus:session:{id}`（P2 SessionStore）；P3 不再新增自造前缀 |
| COSINE 度量 + `_source` 标量字段化 + 标量索引 | T4 `listDocuments` 按 `source` 聚合依赖 P2 T6 已落地（未落地则临时退化并标注依赖） |
| `milvus.collection-name` 配置化 | 本期不扩展多库；管理接口的 source 维度天然基于当前 collection |

### 5.4 阶段衔接

- P3 是最后一阶段，无后续 P4；所有横向约定（第 5 节）在本期落地的接口/配置上固化，作为交付物的一部分。
- 若 T7 评测暴露召回不足，触发的是"检索算法专项"（混合检索/rerank）的独立决策，不改变本期任务边界。

## 6. 验证方案

### 6.1 验收清单

| 任务 | 验证方法 |
|---|---|
| T1 | 配置真实 CLS 凭证 → Agent 查日志返回真实内容；缺凭证非 mock 启动失败；grep 假数据方法命中 0；`buildMethodToolsArray` 无二选一分支；换 `CLS_TOPIC_*` 不改代码生效 |
| T2 | 配置真实 `PROMETHEUS_BASE_URL` → 返回真实 firing 告警；同 alertname 多实例返回聚合（firingCount）；缺 base-url 非 mock 启动失败；basic auth 环境可用 |
| T3 | `/api/rag` 带 Key 流式返回 search→reasoning→content→done；不带 Key 401；thinking 模型有 reasoning、非 thinking 无；`rag.model` 未配置启动失败 |
| T4 | `GET /api/documents` 列表与 Milvus 一致；删除后目标 chunk 归零、其他文档不变；`../../`、含引号文件名 400；三接口无 Key 401 |
| T5 | 管理页文档列表/状态徽标/删除/重建全流程可用；删除后列表刷新、向量同步删 |
| T6 | 无 `localhost:9900` 硬编码；textarea Enter/Shift+Enter/字数提示；流式中"停止"中断、错误后"重试"；上传进度条；会话历史从后端读 |
| T7 | 两个评测脚本可重复执行并输出报告；首期 ≥20 条问题；recall@3 基线记录；坏例可复现 |
| T8 | 四类动作产生审计 JSON 行；单 Key 无回归、多 Key 归属正确；审计无密钥/question 全文 |

### 6.2 RAG 评估"怎么算通过"

- **召回**：首期基线 `recall@3 ≥ 0.7`（在 `aiops-docs/` 5 篇文档的评测集上）；若 < 0.7，先查分片质量（`document.chunk.max-size=800`/`overlap=100`）与 `nprobe`，不轻易判"换检索算法"。
- **答案相关性**：人工标注均分 `≥ 3.5/5`（5 分制：1 完全无关/5 准确完整）；低分（<3）case 必须进坏例集并给出根因（缺文档/分片切坏/模型幻觉）。
- **一致性（LLM-as-judge 启用时）**：judge 与人工标注 Cohen's kappa `≥ 0.6`，否则 judge 结果仅作参考、人工为准。
- **回归**：每次改分片/换模型/改 prompt 后重跑 `eval_retrieval.py` + `eval_answer.py`，recall 与均分不下降才可合并；坏例集单独回归。

### 6.3 上线前检查清单（发布门禁）

- [ ] `application.yml`（及副本）无真实 `sk-`、无 CLS SecretKey、无 MCP 端点 token，仅 `${VAR}` 占位
- [ ] 未配置 `TENCENTCLOUD_SECRET_ID/KEY`、`PROMETHEUS_BASE_URL`、`rag.model` 时启动失败并明确报缺失键名（fail-fast）
- [ ] `/api/rag`、`/api/documents*` 匿名访问 401；静态页正常打开
- [ ] 删除文档的注入文件名 payload（`../../`、含引号）全套 400
- [ ] 审计日志开启且四类动作可追溯；日志无密钥/向量/question 全文
- [ ] 前端无 `localhost` 硬编码；textarea/停止/重试/进度条全功能演示通过
- [ ] 评测脚本基线报告归档；recall@3 与答案均分达到 6.2 阈值
- [ ] 本方案第 5 节横向约定无偏离（P0/P1/P2 约定全部原样沿用）

## 7. 风险与应对

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | CLS 真实环境日志字段与 `LogEntry`（timestamp/level/service/instance）不完全对应 | 映射后字段空、LLM 结果不完整 | T1 映射策略允许缺失置空；首期用真实测试日志集校准字段映射；`getAvailableLogTopics` 的 exampleQueries 与真实 topic 语法对齐 |
| 2 | DashScope SDK 2.17.0 对 thinking 模型 `reasoning_content` 的实际返回字段与预期不符 | reasoning 流式补不齐 | T3 以 SDK 实际字段为准（先写一个最小探针验证 `GenerationResult` message 的 reasoning 字段）；不支持时降级为"无 reasoning"，不阻塞 content |
| 3 | P2 的 `VectorRepository` / `_source` 标量字段未如期落地 | T4 `listDocuments` 聚合无法用索引高效实现 | 临时退化为遍历查询并标注依赖；删除/重建仍可用 `deleteBySource`（依赖 P0/P2 转义，与字段化无关） |
| 4 | `/api/rag` 与 `/api/chat` 的 LLM 调用放大费用（两条链路都烧 token） | 费用上升 | 两条链路明确职责（rag=纯检索问答、chat=工具编排），默认前端只走其一；`rag.model` 选轻量档可降本；必要时对 `/api/rag` 加 P2 Resilience4j 限流 |
| 5 | 删除文档误删（转义遗漏或白名单绕过） | 数据丢失 | 三重防护沿用 P0（文件名白名单 + `escapeExprString` + 越界校验）；删除前审计日志记录 source；上线前注入 payload 全套回归（6.1 T4） |
| 6 | 前端"停止生成"中断流后，后端 SSE/线程未及时回收 | 线程泄漏 | 复用 P2 有界 `sseExecutor` 与 `AbortController` 联动；`completeWithError`/超时兜底；`argus.sse.sessions.active` 监控 |
| 7 | 多 Key 映射引入后，审计归属错误（Key 泄露被冒用） | 审计误导 | 明确 Key 仍是共享口令（D6）；Key 轮换流程沿用 P0 T1；审计记录 operator + traceId，便于事后核对 |
| 8 | 评测集质量差（标注不准/覆盖窄）导致阈值失真 | 评估失去意义 | 首期人工构造并双人复核；评测集与坏例纳入版本管理；recall 阈值作为"基线"而非硬指标，先度量再定目标 |
| 9 | 审计日志文件无限增长/含敏感信息 | 磁盘耗尽、二次泄露 | P1 logback 滚动策略；审计内容只存哈希+摘要；`argus.audit.events` 监控异常突增 |
| 10 | CLS/Prometheus 是外部新故障面（网络抖动/限流） | 工具调用失败、问答质量下降 | `ClsLogClient`/`fetchPrometheusAlerts` 超时+重试（复用 P2 Resilience4j 或 SDK 内建重试）；失败返回脱敏错误 JSON，Agent 可引导用户重试 |
