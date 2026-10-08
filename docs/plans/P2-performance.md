# P2 阶段方案：性能与扩展

> 适用工程：`d:\代码\SuperBizAgent-release-2026-05-17`（Maven 单模块，Java 17 + Spring Boot 3.2.0 + Spring AI Alibaba 1.1.0.0-RC2 + DashScope（通义）+ Milvus 2.6.x，入口 `org.example.Main`，前端为 `src/main/resources/static` 下原生 HTML/JS）。
> 前置：P0（安全止血，见 `docs/plans/P0-security.md`）与 P1（工程化底座）已定约定，本方案第 5 节声明沿用情况。
> 文中所有路径、行号、方法名均基于当前代码快照逐项核实；行号在后续修改后会漂移，以"文件 + 方法/代码特征"为准。性能收益数字一律为**估算**，需以第 6 节压测实测为准。

## 1. 背景与目标

### 1.1 背景

P0 消除了安全硬阻塞、P1 建立了工程化底座之后，服务可安全上线，但性能与可扩展性存在多处硬瓶颈。经逐项核查源码，当前问题如下：

| # | 问题 | 核实位置（当前快照） |
|---|------|----------------------|
| 1 | 索引链路逐分片串行：每分片单独调一次 embedding HTTP | `src/main/java/org/example/service/VectorIndexService.java` 第 146-165 行分片循环，第 151 行 `embeddingService.generateEmbedding(chunk.getContent())` 逐条远程调用 |
| 2 | 批量向量化接口已实现但无人调用 | `src/main/java/org/example/service/VectorEmbeddingService.java` 第 152 行 `generateEmbeddings(List<String>)`，全工程检索无调用方 |
| 3 | 每分片 loadCollection、每分片单条 insert、每次 insert new Gson | `VectorIndexService.java` 第 259-263 行（insertToMilvus 内 loadCollection）、第 277-288 行（单条 Field 构建，第 286 行 `new com.google.gson.Gson()`）、第 291-297 行（insert） |
| 4 | 上传接口同步建索引，索引失败静默吞掉 | `src/main/java/org/example/controller/FileUploadController.java` 第 72-80 行：同步调 `indexSingleFile`（第 74 行），catch 后仅记日志（第 76-80 行），仍返回 200，调用方无从感知索引成败 |
| 5 | 线程池无界且无优雅关闭 | `src/main/java/org/example/controller/ChatController.java` 第 52 行 `Executors.newCachedThreadPool()`，无上限、无 `@PreDestroy` |
| 6 | 会话 Map 无 TTL、sessionId 生成后不回传、流式累加器线程不安全 | `ChatController.java` 第 55 行 `sessions` 内存 Map 无过期；第 403-408 行 `getOrCreateSession` 在 id 为空时 `UUID.randomUUID()` 生成新 id 但响应中不回传（前端 `app.js` 第 6/267/470 行自造 id，第 614/670 行请求带 `Id`，后端生成路径导致多轮续接断裂）；第 186 行 `StringBuilder fullAnswerBuilder` 在第 203 行被流式回调跨线程 append |
| 7 | 检索度量与索引类型不匹配：text-embedding-v4 输出归一化向量却用 L2 | `src/main/java/org/example/service/VectorSearchService.java` 第 56 行 `MetricType.L2`、第 58 行 `nprobe=10`；`src/main/java/org/example/client/MilvusClientFactory.java` 第 166-168 行 `IVF_FLAT + L2 + nlist=128`；模型 `text-embedding-v4` 配置于 `application.yml` 第 57 行 |
| 8 | 按路径删除旧数据走 JSON 字段过滤（全表扫） | `VectorIndexService.java` 第 181 行 `metadata["_source"] == "..."` 表达式删除；schema（`MilvusClientFactory.java` 第 130-133 行）只有 JSON 字段无标量字段，P0 第 5.4 节已约定按 D4-B 方向在索引重构时字段化 |
| 9 | 整文件读入内存无上限 | `VectorIndexService.java` 第 135 行 `Files.readString(path)` |
| 10 | 每次请求重建 DashScopeApi/ChatModel | `ChatController.java` 第 83-84 行（/api/chat）、第 171-172 行（/api/chat_stream）、第 292-301 行（/api/ai_ops），每次调 `chatService.createDashScopeApi()` / `createStandardChatModel()` |
| 11 | 会话仅存本机内存，无法多实例部署 | `ChatController.java` 第 55 行；无 Redis 等外置存储 |
| 12 | 无应用容器化部署物 | 根目录 `vector-database.yml` 仅有 Milvus 编排（etcd/minio/standalone v2.5.10/attu），无应用镜像与 Redis |

### 1.2 目标

1. **索引吞吐**：向量索引链路批量化，单文件索引耗时估算降低 80% 以上（估算，见 T2 收益分析）。
2. **接口语义**：上传与索引解耦，索引失败从"静默吞掉"变为"任务可查询、可重试"；响应语义明确。
3. **资源治理**：线程池有界、会话有 TTL、累加器线程安全，消除 OOM 与线程失控风险。
4. **水平扩展**：会话外置 Redis，为多实例部署打基础；容器化部署物交付。
5. **检索质量与效率**：度量类型迁移 COSINE、`_source` 标量字段化 + 标量索引，删除与检索不再全表扫。
6. **对外契约**：产出索引任务 API、Redis key 规范、Repository 落点等横向约定，供 P3 直接复用（第 5 节）。

## 2. 范围界定（本期做什么 / 明确不做什么）

### 2.1 本期做什么

| 任务 | 内容 |
|---|---|
| T1 | 数据访问层抽取 `org.example.repository.VectorRepository`（P2-高） |
| T2 | 向量索引链路批量化：批量 embedding、批量 insert、每文件一次 loadCollection、Gson 静态复用（P2-高） |
| T3 | 索引异步任务化：任务 ID、状态查询接口、失败重试、定时重建目录索引（P2-高） |
| T4 | 会话与并发治理：有界线程池 + 优雅关闭、sessionId 回传修复、累加器线程安全（P2-高） |
| T5 | 会话外置 Redis：key 设计、TTL、Jackson 序列化、降级回退内存（P2-中） |
| T6 | 检索与存储结构调优：COSINE 迁移（新 collection 切换）、`_source` 字段化 + 标量索引、参数调优与 HNSW 阈值（P2-中） |
| T7 | 其它性能项：文件大小限制、ChatModel 单例、embedding 结果缓存、LLM/embedding 限流与超时重试（P2-中） |
| T8 | 容器化：多阶段 Dockerfile + docker-compose（应用 + Milvus + Redis）（P2-中） |

### 2.2 明确不做什么（留待后续阶段或明确不做）

- 不引入消息队列（Kafka/RocketMQ）做索引解耦——单机任务量级用进程内有界线程池足够，MQ 属运维成本显著的新组件（见 D1）。
- 不做 Milvus 分布式集群、分片数调优、多 replica——沿用 standalone 部署（`vector-database.yml` 形态）。
- 不做真正的流式文件分片（边读边切）——`DocumentChunkService.chunkDocument()`（`DocumentChunkService.java` 第 35 行起）按 Markdown 标题/段落对**全文**做结构化分片，流式化需重写分片器，收益有限；本期以"文件大小上限"防 OOM（T7）。
- 不更换 embedding 模型与维度（`text-embedding-v4`、1024 维不变，`MilvusConstants.VECTOR_DIM` 第 18 行）；不做多路召回、混合检索（BM25+向量）、rerank——检索算法升级属 P3 产品化。
- 不做 LLM 推理加速、token 级缓存、对话内容语义缓存。
- 不做用户级配额/计费、多租户（P3）。
- 不做 K8s/Helm 编排——docker-compose 覆盖当前交付形态。
- 前端仅做配合性改动（sessionId 透传、任务状态轮询），不做前端性能优化与重构。

### 2.3 建议实施顺序（依赖驱动）

```
T1（Repository 抽取，纯搬移不改行为）
 → T2（批量化，落在 Repository.insertBatch 上）
 → T3（异步任务化，复用 T2 链路并落地错误语义）
 → T6（新 collection 结构切换，依赖 T1/T2 的写入链路）
 → T4（并发与会话治理，独立于索引链路）
 → T5（Redis 外置，依赖 T4 的 SessionStore 形态）
 → T7（杂项性能，embedding 缓存依赖 T5 的 Redis）
 → T8（容器化收尾，依赖全部配置项定型）
```

## 3. 任务清单

> 优先级定义：**P2-高**（主链路性能/稳定性硬瓶颈，直接决定上传索引耗时与并发安全）｜**P2-中**（扩展性与运维效率，影响多实例演进与检索质量）。
> 所有新增公共类落点遵循 P1 约定：公共组件 `org.example.common`、工具类 `org.example.util`；P2 新增业务分层 `org.example.repository`、`org.example.task`。

---

### T1｜P2-高｜抽取 VectorRepository 数据访问层

**目标**：将 Service 直接拼装 Milvus gRPC 参数的代码下沉到 Repository，Service 只做编排；为 T2 批量化与 Mockito 单测（mock Repository 即可，不必拉起 Milvus）铺路。

**涉及文件**
- 新增（允许）：`src/main/java/org/example/repository/VectorRepository.java`
- 修改：`src/main/java/org/example/service/VectorIndexService.java`（第 173-215 行 `deleteExistingData`、第 255-309 行 `insertToMilvus`）、`src/main/java/org/example/service/VectorSearchService.java`（第 51-66 行搜索参数构建）
- 测试：`src/test/java/org/example/repository/VectorRepositoryTest.java`（Mockito 对 MilvusServiceClient 打桩）

**具体改动**
1. `VectorRepository` 封装以下方法（第一阶段原样搬移现有逻辑，不改行为）：
   ```java
   @Repository
   public class VectorRepository {
       private final MilvusServiceClient client;
       private static final Gson GSON = new Gson();          // 静态复用（Gson 线程安全），替代第 286 行每次 new

       public void loadCollectionOnce() { ... }              // 搬移 insertToMilvus 第 259-267 行，容忍 65535（已加载）
       public long deleteBySource(String normalizedPath) { ... }   // 搬移 deleteExistingData 第 180-209 行
                                                              // 表达式拼装必须经 P0 约定的 escapeExprString()
       public void insertBatch(List<InsertRow> rows) { ... } // T2 实现；InsertRow 为 id/content/vector/metadataJson 的内部行模型
       public List<SearchResultRow> search(List<Float> queryVector, int topK) { ... }  // 搬移 VectorSearchService 第 51-66 行
   }
   ```
2. `VectorIndexService` / `VectorSearchService` 删除 gRPC 细节，改为注入 `VectorRepository` 编排调用。
3. `MilvusClientFactory`（schema/索引创建）保持不动——建表属初始化职责，不进 Repository；T6 再改 schema。

**验收标准**
- 全部现有行为不变（上传→索引→检索→按 `_source` 删除回归通过）；
- `VectorIndexService`/`VectorSearchService` 中不再出现 `io.milvus.param.*` 的 Builder（grep 验证）；
- `VectorRepositoryTest` 覆盖 loadCollection 容错（65535）、delete 表达式转义、insert 字段构建，全部用 Mockito，不依赖真实 Milvus。

---

### T2｜P2-高｜向量索引链路批量化

**目标**：消除"逐分片一次 HTTP + 一次 loadCollection + 一次 insert + 一次 new Gson"的四重串行开销。

**涉及文件**
- `src/main/java/org/example/service/VectorIndexService.java`（第 124-168 行 `indexSingleFile`，问题点第 146-165、259-263、277-297 行）
- `src/main/java/org/example/service/VectorEmbeddingService.java`（第 152 行 `generateEmbeddings` 启用；顺带补批量结果与输入条数一致性校验）
- `src/main/java/org/example/repository/VectorRepository.java`（T1 产出，实现 `insertBatch`）
- `src/main/resources/application.yml`（新增 `dashscope.embedding.batch-size`、`index.insert.batch-size`）

**具体改动**

`indexSingleFile` 主流程批量化（伪代码）：

```java
public void indexSingleFile(String filePath) throws Exception {
    // ... 前置校验、readContentWithLimit（T7）、deleteBySource（T1）不变 ...
    List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());

    // 1) 批量向量化：按 DashScope 批量上限分批（text-embedding-v4 批量上限 B，
    //    以官方文档为准，配置 dashscope.embedding.batch-size 默认 10；条数 > B 时自动切分）
    List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
    List<List<Float>> vectors = new ArrayList<>(chunks.size());
    for (List<String> batch : partition(texts, embeddingBatchSize)) {
        vectors.addAll(embeddingService.generateEmbeddings(batch));   // 第 152 行既有接口，启用
    }
    // 校验 vectors.size() == chunks.size()，不一致抛 BizException（P1 异常体系），防止错位写入

    // 2) 每文件 loadCollection 一次（原为每分片一次）
    repository.loadCollectionOnce();

    // 3) 批量 insert（默认 256 行/批，Milvus 单批实体数建议 <=1000 量级）
    for (List<InsertRow> rows : partition(toRows(chunks, vectors, path), insertBatchSize)) {
        repository.insertBatch(rows);   // InsertRow 复用现有 id 规则：
                                        // UUID.nameUUIDFromBytes((source + "_" + chunkIndex))（原第 271 行）
    }
}
```

`insertBatch` 字段构建要点：id/content/vector/metadata 四个 `InsertParam.Field` 的列表长度必须等于批大小（Milvus 列式插入），metadata 的 `JsonObject` 用静态 `GSON` 生成（替代原第 286 行每次 `new Gson()`）。

**失败语义**：分片级失败不再导致"第 N 片成功、第 N+1 片抛异常留下脏数据"的半成品状态——批量失败时整文件任务标记 FAILED（T3），重跑前先 `deleteBySource` 清旧数据（现有第 139 行逻辑已在文件级 delete，天然支持重跑幂等）。

**预期收益（估算，压测前不作承诺）**
以一份约 500KB 的 Markdown 文档（`document.chunk.max-size=800`、`overlap=100`，约产出 350-400 个分片）为例：
| 项 | 现状 | 批量化后 | 说明 |
|---|---|---|---|
| embedding HTTP 调用 | ~400 次 ×（RTT+计算，估算 200-400ms/次） | ~40 次（10 条/批） | 估算耗时从 80-160s 降至 16-32s |
| loadCollection | ~400 次 | 1 次 | 省去重复 RPC 与服务端冗余检查 |
| insert gRPC | ~400 次（每批 1 行） | ~2 次（256 行/批） | gRPC 往返与序列化开销摊薄 |
| Gson 实例化 | ~400 次 | 0（静态复用） | 微小但零成本 |
| **单文件索引总耗时** | 估算 80-160s | **估算 17-33s（降低约 80%）** | 受 DashScope 限流影响，实测为准 |

**验收标准**
- 同一文档改造前后索引耗时对比记录在案（方法见 6.1），实测降幅 ≥60%（若未达标，检查 DashScope 批量接口限流与分批大小）；
- Milvus 中该文件的 chunk 数、id 规则（`UUID.nameUUIDFromBytes(source_chunkIndex)`）、metadata 内容与改造前完全一致（Attu 或 query 抽样比对）；
- 批量 embedding 返回条数与输入不一致时整文件失败且无脏数据残留；
- `VectorEmbeddingService.generateEmbeddings` 单测覆盖（空列表、部分失败、结果顺序）。

---

### T3｜P2-高｜索引异步任务化（含定时重建）

**目标**：上传接口立即返回任务 ID；索引异步执行、可查询、可重试；落地 `VectorIndexService.java` 第 52 行自留 TODO（"定时重建目录下所有文件的索引"）。

**涉及文件**
- 新增（允许）：`src/main/java/org/example/task/IndexTaskManager.java`（任务状态机与存储）、`src/main/java/org/example/controller/IndexTaskController.java`（任务 API）、`src/main/java/org/example/dto/IndexTask.java`（任务模型）
- 修改：`src/main/java/org/example/controller/FileUploadController.java`（第 72-80 行同步索引改为提交任务）、`src/main/java/org/example/Main.java`（`@SpringBootApplication` 上加 `@EnableScheduling`）
- `src/main/resources/application.yml`（`index.task.*`、`index.rebuild.cron` 配置）
- `src/main/resources/static/app.js`（上传后轮询任务状态，配合改动）

**具体改动**

1. **任务模型与状态机**：
   ```
   PENDING → RUNNING → SUCCESS
                     ↘ FAILED →（人工/自动重试，retry < max）PENDING → RUNNING ...
   ```
   任务属性：`taskId`（UUID）、`type`（FILE / DIRECTORY_REBUILD）、`target`（文件或目录路径）、`status`、`progress`（如 `12/40` 分片）、`failReason`（经 P0 约定脱敏，不含密钥/向量）、`createTime/startTime/endTime`、`retryCount`。

2. **IndexTaskManager**：有界单线程执行（索引天然可串行，避免同文件并发重建与 DashScope 限流放大）：
   ```java
   // 执行器：core=max=1，queue 有界（默认 100），满则拒绝并返回明确错误（HTTP 503，统一错误体）
   ThreadPoolTaskExecutor indexExecutor;   // setQueueCapacity(100), 拒绝策略 AbortPolicy

   public String submitFile(String filePath) {     // 上传链路调用
       String taskId = UUID.randomUUID().toString();
       tasks.put(taskId, IndexTask.pending(FILE, filePath));   // 内存 ConcurrentHashMap 为权威源
       saveToRedis(taskId);                                     // 可用则写 sba:index:task:{taskId}，TTL 24h（供多实例/P3 查询）
       indexExecutor.execute(() -> runWithRetry(taskId));
       return taskId;
   }

   private void runWithRetry(String taskId) {
       // RUNNING → 批量索引（T2 链路）→ SUCCESS；异常 → FAILED + failReason（Jackson 序列化，遵守 P0 T8 约定）
       // 自动重试：仅对网络类异常重试（max-attempts 可配，默认 2），指数退避
   }

   @Scheduled(cron = "${index.rebuild.cron:-}")     // 默认 "-" 表示禁用，按环境显式开启
   public void scheduledRebuild() {
       // 落地 VectorIndexService 第 52 行 TODO：对 file.upload.path 目录全量重建
       // 单实例直接执行；多实例部署时用 Redis SETNX 锁（key: sba:index:rebuild-lock）保证只跑一份
   }
   ```

3. **任务 API**（`IndexTaskController`，挂 `/api/**` 下自动纳入 P0 的 X-API-Key 鉴权；响应体遵循统一格式成功 `{"code":0,...}`）：
   - `POST /api/index/tasks`：触发目录重建（body 可选 `directoryPath`），返回 `{"code":0,"data":{"taskId":"..."}}`；
   - `GET /api/index/tasks/{taskId}`：返回状态 + 进度 + 失败原因；
   - `GET /api/index/tasks`：任务列表（分页，默认最近 50 条）。
   - 三个接口的路径与语义为跨阶段横向约定（第 5.3 节），P3 管理界面直接复用。

4. **上传接口语义**（`FileUploadController.java` 第 72-80 行改造）：
   ```java
   // 文件落盘成功后：
   String taskId = indexTaskManager.submitFile(filePath.toString());
   // 响应 data 增加 taskId 与 indexed=false：
   // {"code":0,"message":"success","data":{"fileName":...,"filePath":...,"fileSize":...,"taskId":"...","indexed":false}}
   ```
   **明确索引失败语义**：上传接口保持 HTTP 200（文件落盘成功即上传成功），索引成败通过 `taskId` 查询——`FAILED` 时返回 `failReason` 并支持重试（`POST /api/index/tasks` 带 taskId 重试，或 P3 界面触发）。不再出现"200 但索引静默失败"的不可观测状态。不采用 207 Multi-Status：单文件上传是单一资源操作，207 留给将来批量上传接口。

5. **前端配合**（`app.js`）：上传成功后保存 `taskId`，在结果区轮询 `GET /api/index/tasks/{taskId}`（间隔 2s，最多 5 分钟）展示"索引中 x/y → 索引完成/索引失败（可重试）"。

**验收标准**
- 上传 5MB 文档接口响应 < 1s（落盘即返回，估算）；`taskId` 可查到 PENDING→RUNNING→SUCCESS 全程状态与进度；
- 人为停掉 DashScope（错误 Key）触发索引失败：任务 FAILED、`failReason` 为脱敏文本、可重试、重试成功后数据完整；
- `index.rebuild.cron` 配置 `0 0 3 * * *` 后，3 点自动产生 DIRECTORY_REBUILD 任务并完成；重复调度不并发执行；
- 任务队列打满（>100）时上传返回 HTTP 503 统一错误体，而非无界堆积；
- 全部任务 API 无 `X-API-Key` 访问时 401（沿用 P0 鉴权）。

---

### T4｜P2-高｜会话与并发治理

**目标**：消除无界线程池、会话泄漏、多轮续接 bug、流式累加器竞态四个资源/正确性问题。

**涉及文件**
- `src/main/java/org/example/controller/ChatController.java`（第 52、55、145、186、203、403-408 行）
- 新增（允许）：`src/main/java/org/example/config/ExecutorConfig.java`（线程池 Bean）
- `src/main/resources/application.yml`（`sbe.executor.*` 配置）
- `src/main/resources/static/app.js`（sessionId 透传，第 614、670 行请求体附近）

**具体改动**

1. **有界线程池 + 优雅关闭**（替代第 52 行 `Executors.newCachedThreadPool()`）：
   ```java
   @Bean("sseExecutor")
   public ThreadPoolTaskExecutor sseExecutor() {
       ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
       ex.setCorePoolSize(8);  ex.setMaxPoolSize(16);
       ex.setQueueCapacity(100);                          // 有界队列
       ex.setThreadNamePrefix("sba-sse-");
       ex.setWaitForTasksToCompleteOnShutdown(true);      // 优雅关闭
       ex.setAwaitTerminationSeconds(30);
       ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
       return ex;   // Spring 容器托管生命周期，替代手工 executor，天然具备 @PreDestroy 语义
   }
   ```
   队列满（并发 >116 路在途）时 `TaskRejectedException` 由 P1 的 `GlobalExceptionHandler` 兜底返回 HTTP 503 + 统一错误体（`{"code":503,"message":"服务繁忙，请稍后重试","data":null}`，脱敏文案）。

2. **sessionId 回传修复**（第 403-408 行 `getOrCreateSession`）：
   - `POST /api/chat`（非流式）：`ChatResponse` 增加 `sessionId` 字段，`getOrCreateSession` 返回的实际 id（复用请求传入的，或后端新生成的）随响应回传；
   - `POST /api/chat_stream`（SSE）：订阅建立后**首帧**发送 `SseMessage` 扩展类型 `{"type":"session","data":"<sessionId>"}`（在现有 content/error/done 之外新增一种类型，向后兼容），前端优先采用后端回传 id 覆盖本地 `this.sessionId`（`app.js` 第 614、670 行请求体继续用 `Id` 字段回传）；
   - 由此，"前端未带 Id→后端生成新 id→前端不知情→下一轮又生成新会话"的续接断裂被消除。

3. **流式累加器线程安全**（第 186、203 行）：
   `StringBuilder fullAnswerBuilder` 改为 `StringBuffer`（或 `synchronized` 块）。理由：Reactor 规范保证单个订阅者信号串行，但 Spring AI Alibaba graph 的 `Flux<NodeOutput>` 由多节点执行线程汇聚产生，保守按并发访问处理；改动一行、零语义变化。

4. **内存会话兜底淘汰**（第 55 行 `sessions`）：在 T5 Redis 外置前先加两条底限（T5 落地后内存 Map 仅作降级存储，同样保留）：
   - 会话上限（默认 10000，超出按 LRU 淘汰最久未访问会话）；
   - 定时清理：`@Scheduled` 每小时清理 `createTime` 超过 `session.ttl`（默认 24h）的会话，防止泄漏。

**验收标准**
- 压测 150 并发 `/api/chat_stream`：池满后新请求得到 503 统一错误体，无 OOM、无线程数失控（线程数上限 16+16 前端连接线程，jstack 验证）；
- 应用 `SIGTERM` 关闭时在途 SSE 任务最多 30s 内完成收尾，日志出现优雅关闭记录；
- 前端不带 `Id` 连续两轮对话，第二轮请求携带第一轮响应/SSE 首帧回传的 `sessionId`，`GET /api/chat/session/{sessionId}` 显示消息对数累加（多轮续接修复）；
- 单测：`StringBuffer` 累加并发正确性（多线程 append 后长度校验）、会话 LRU 淘汰与 TTL 清理。

---

### T5｜P2-中｜会话外置 Redis

**目标**：多轮上下文存 Redis，应用无状态化，为多实例部署与 P3 打基础；Redis 故障时降级内存并告警，不阻断服务。

**涉及文件**
- 新增（允许）：`src/main/java/org/example/common/session/SessionStore.java`（接口）、`RedisSessionStore.java`、`InMemorySessionStore.java`
- 修改：`src/main/java/org/example/controller/ChatController.java`（第 55 行 `sessions`、`SessionInfo` 第 416-505 行改为经 SessionStore 读写）
- `pom.xml`（新增 `spring-boot-starter-data-redis`，理由见 D4）
- `src/main/resources/application.yml` / `application.yml.example`（Redis 配置）

**具体改动**

1. **配置**（遵守 P0 5.1：敏感值无默认值外置）：
   ```yaml
   spring:
     data:
       redis:
         host: ${REDIS_HOST:localhost}     # 非敏感，可留默认
         port: ${REDIS_PORT:6379}
         password: ${REDIS_PASSWORD}       # 敏感，无默认值，缺失即启动失败（fail-fast 同 P0 T1）
   session:
     ttl: 24h                              # 会话过期时间
   ```

2. **key 设计与序列化**（key 规范为跨阶段横向约定，见 5.3）：
   ```
   sba:session:{sessionId}   → value: Jackson 序列化的 {"messages":[{"role":"user","content":"..."},...],
                               "createTime":1690000000000}，TTL = session.ttl（每次读写滑动刷新）
   ```
   序列化一律 Jackson（P0 5.3"JSON 构造一律 Jackson"约定的自然延伸；不引 RedisTemplate 默认 JDK 序列化——跨版本兼容差且 payload 大）。使用 `StringRedisTemplate` + 显式 `ObjectMapper`（复用 `WebConfig` 已注册的 Bean）。

3. **读写路径**：`getOrCreateSession`/`addMessage`/`clearHistory`/`getSessionInfo` 全部改走 `SessionStore`；`MAX_WINDOW_SIZE=6`（`ChatController.java` 第 58 行）的窗口裁剪逻辑保持在写路径（读改写回）。

4. **降级策略**：
   ```java
   public SessionStore sessionStore(StringRedisTemplate t) {
       return new FallbackSessionStore(new RedisSessionStore(t), new InMemorySessionStore());
       // FallbackSessionStore：捕获 RedisConnectionFailureException / RedisSystemException
       // → logger.error("Redis 不可用，会话降级内存") + sba.redis.fallback 计数 + 切换内存实现
       // Redis 恢复后：健康检查探活成功即自动切回（降级期间产生的内存会话不回迁，接受该有损语义并在日志明示）
   }
   ```
   告警出口：`sba.redis.fallback` 计数指标（Micrometer，P1 可观测体系）+ ERROR 日志（内容脱敏，不含会话正文与密钥）。

**验收标准**
- 重启应用后携带同一 `sessionId` 续聊，历史上下文完整（会话不再随进程丢失）；
- `redis-cli` 可见 `sba:session:*` key、TTL 随访问刷新、过期自动清除；value 为合法 JSON；
- 停掉 Redis：对话功能不中断（内存降级），日志与 `sba.redis.fallback` 指标出现；恢复 Redis 后自动切回；
- 未设置 `REDIS_PASSWORD` 且 Redis 要求认证时，启动失败并报缺失键名（P0 fail-fast 语义）；
- 未部署 Redis 的最小环境（单机演示）可配置显式开关 `session.store=memory` 降级运行（默认 redis）。

---

### T6｜P2-中｜检索调优：COSINE 迁移 + _source 字段化 + 参数调优

**目标**：度量类型与归一化向量模型匹配；按路径删除从 JSON 全表扫改为标量字段命中；给出数据量增长后的索引类型演进路径。

**涉及文件**
- `src/main/java/org/example/client/MilvusClientFactory.java`（第 109-156 行 schema、第 161-178 行 createIndexes）
- `src/main/java/org/example/service/VectorSearchService.java`（第 56、58 行）
- `src/main/java/org/example/repository/VectorRepository.java`（T1 产出，`deleteBySource` 改标量字段表达式）
- `src/main/java/org/example/constant/MilvusConstants.java`（第 13 行 `MILVUS_COLLECTION_NAME = "biz"` 改为配置注入）
- `src/main/resources/application.yml`（新增 `milvus.collection-name`、`milvus.search.nprobe`）

**具体改动**

1. **`_source` 字段化**（落地 P0 D4-B 既定方向）：
   - 新 schema 增加 `source` 字段：`VarChar`、`MaxLength=1024`（覆盖 uploadDir 前缀 + 文件名；文件名本身仍受 P0 `SAFE_FILENAME_REGEX` 约束）、建**标量索引**（Milvus inverted index，2.6 默认推荐）；`metadata` JSON 字段保留（`_source` 键仍写入，兼容存量读取），读取侧逐步切到 `source`。
   - `deleteBySource` 表达式从 `metadata["_source"] == "..."`（原第 181 行）改为 `source == "..."`（仍经 `escapeExprString()` + 文件名白名单，P0 5.3 双保险不变）。

2. **COSINE 迁移**（text-embedding-v4 输出归一化向量；归一化下 L2 与 COSINE 排序等价，但 COSINE 分数语义正确、可解释，且避免非归一化输入时排序错误的隐患）：
   - 新 collection 向量索引：`IVF_FLAT + COSINE + nlist=128`（`MilvusClientFactory.java` 第 166-168 行对应参数）；查询侧 `MetricType.COSINE`（`VectorSearchService.java` 第 56 行）。
   - **注意分数语义反转**：L2 越小越相似、COSINE 越大越相似——`SearchResult.score` 消费方（前端展示、`InternalDocsTools` 返回给 LLM 的分数字段）同步确认。
   - **迁移步骤**（schema 与度量不可在线修改，必须换表）：
     1. **建新表**：`biz_v2`（新 schema：+source 字段、COSINE 索引、标量索引）；collection 名改为配置 `milvus.collection-name`（默认 `biz`），`MilvusConstants.MILVUS_COLLECTION_NAME` 的静态引用点（`MilvusClientFactory`/`VectorIndexService`/`VectorSearchService`/`MilvusCheckController`）统一改注入；
     2. **回填**：源文件都在 `file.upload.path` 目录，直接用 T3 的目录重建任务对 `biz_v2` 全量重索引（等效"新表回填"，无需从旧表导数；期间旧表 `biz` 保持只读服务，检索仍走旧表）；
     3. **切换**：改 `milvus.collection-name=biz_v2`（环境变量 `MILVUS_COLLECTION_NAME`）重启，验证检索/删除/上传全链路；
     4. **旧表下线**：观察一个重建周期（或至少 3 天）无回退需求后 drop `biz`（drop 前可 `query` 导出备份）。
   - 双写不采用：写入链路唯一入口是本服务，切换窗口短，全量重建成本低（见 D6）。

3. **检索参数调优**：
   - `nprobe` 从 10 提到 16（`VectorSearchService.java` 第 58 行，配置化 `milvus.search.nprobe`）；召回率/延迟权衡以 6.1 压测为准；
   - `nlist` 经验公式 `4×sqrt(N)`（N=向量数），当前 nlist=128 适配约 1 万~100 万向量，暂不动；
   - **HNSW 迁移阈值（建议，不在本期执行）**：向量数 > 10 万且检索 P95 > 50ms，或 QPS 上量后 IVF_FLAT 召回调参无效时，切 `HNSW + COSINE`（M=16、efConstruction=200、查询 ef=64）；HNSW 内存占用更高（估算 +50% 索引内存），需评估 standalone 实例规格。

**验收标准**
- `biz_v2` 表结构：`source` 标量字段 + 标量索引 + COSINE 向量索引（Attu 或 `describeCollection` 验证）；
- 同一 query 在新旧表的 top-3 召回排序一致（归一化向量下 L2/COSINE 等价性验证）；
- 按 source 删除：`query` 计划验证走标量索引不再全表扫（Milvus query 界面/日志的耗时对比，大表下估算从秒级降到毫秒级——标注估算）；
- 迁移四步全部有操作记录，切换后 24h 内无回退事件，旧表 drop 有备份确认；
- collection 名配置化后，不改代码仅改配置即可完成切换。

---

### T7｜P2-中｜其它性能项：文件上限、模型单例、embedding 缓存、限流与重试

**目标**：收敛内存与远程调用的无节制开销，给 LLM/embedding 两类付费远程调用加限流、超时、重试护栏。

**涉及文件**
- `src/main/java/org/example/service/VectorIndexService.java`（第 135 行 `Files.readString`）
- `src/main/java/org/example/service/ChatService.java`（第 53-57 行 `createDashScopeApi`、第 80-82 行 `createStandardChatModel` 的调用方）
- `src/main/java/org/example/controller/ChatController.java`（第 83-84、171-172、292-301 行每请求重建）
- `src/main/java/org/example/service/VectorEmbeddingService.java`（第 76、152 行调用前加缓存与限流）
- `pom.xml`（新增 `resilience4j-spring-boot3` + `resilience4j-micrometer`，理由见 D8）
- `src/main/resources/application.yml`（`index.max-file-size`、`resilience4j.*`、`cache.embedding.*`）

**具体改动**

1. **文件大小上限**（第 135 行）：`Files.readString` 前校验 `Files.size(path)`，超过 `index.max-file-size`（默认 10MB）抛 `BizException`（P1 异常体系 → HTTP 400，统一错误体），任务状态 FAILED + 明确 failReason。不做流式分片的理由见 2.2。

2. **ChatModel 单例化**：`ChatService` 中将 `DashScopeApi` + 两档 `DashScopeChatModel` 注册为 Bean（`standardChatModel`：temperature 0.7/maxToken 2000/topP 0.9，供 `/api/chat` 与 `/api/chat_stream`；`aiOpsChatModel`：temperature 0.3/maxToken 8000，供 `/api/ai_ops`——参数分别对应当前第 82、293-301 行的每请求构建值）。`DashScopeChatModel` 是无状态 HTTP 封装，单例线程安全；`ChatService.createChatModel` 保留给偶发的参数化场景。每次请求省去一次对象构建与底层 client 初始化（估算：单请求节省微秒~毫秒级，主要收益是消除重复创建带来的 GC 压力与连接 churn——标注估算，实测为准）。

3. **embedding 结果缓存**（Redis，依赖 T5）：
   ```java
   // key: sba:cache:embedding:{sha256(text)}，value: JSON 数组（1024 维浮点），TTL 默认 7d（cache.embedding.ttl）
   // generateEmbedding / generateEmbeddings 入口：
   //   1) 计算各文本 sha256，mget 批量查缓存；
   //   2) 仅对 miss 的文本调 DashScope（批量），结果回填缓存；
   //   3) 按原顺序拼装返回（缓存命中 + 新生成）。
   ```
   收益场景：定时重建目录索引（同文本全命中，估算省 90%+ embedding 调用费）、失败重试、重复上传同内容文件（估算——标注）。缓存内容为向量浮点数组，日志与异常信息不得打印（P0 日志脱敏约定）。

4. **限流 / 超时 / 重试**（Resilience4j，挂 P1 Micrometer 指标）：
   ```yaml
   resilience4j:
     ratelimiter:
       instances:
         embedding: { limitForPeriod: 4, limitRefreshPeriod: 1s }   # 对齐 DashScope embedding QPS 配额
         llm:       { limitForPeriod: 8, limitRefreshPeriod: 1s }
     retry:
       instances:
         embedding: { maxAttempts: 3, waitDuration: 500ms, enableExponentialBackoff: true, backoffMultiplier: 2 }
     timelimiter:
       instances:
         embedding: { timeoutDuration: 30s }
   ```
   注入点：`VectorEmbeddingService.generateEmbedding(s)` 外层、LLM 调用（`ChatService.executeChat`/流式订阅）外层；限流触发时任务转 FAILED（可重试），对话接口返回 429/503 统一错误体。重试仅用于幂等的 embedding 读类调用；LLM 流式不自动重试（SSE 已推送部分内容，重试会导致重复输出，仅超时中断并报错）。

**验收标准**
- 上传 >10MB 文件：HTTP 400 + 统一错误体，`Files.readString` 未执行（无 OOM 风险）；
- 压测下 `DashScopeChatModel` 为同一实例（日志/内存 dump 验证），对话功能回归通过；
- 同一文本第二次向量化走缓存（`sba.cache.embedding.hit` 指标增长，DashScope 调用计数不增长）；定时重建任务第二次运行 embedding 调用数估算下降 >90%（估算，以指标实测）；
- embedding 限流：并发超过 4 QPS 时后续调用排队/受限，DashScope 控制台无 429 风暴；限流/重试指标可在 P1 指标体系（Micrometer）中查到；
- 单测：缓存命中/miss 拼装顺序正确性、大小上限边界（恰好 10MB / 10MB+1）。

---

### T8｜P2-中｜容器化：Dockerfile + docker-compose

**目标**：交付可复现的一键部署物（应用 + Milvus + Redis），配置全部环境变量外置。

**涉及文件**
- 新增（允许）：`Dockerfile`（项目根目录）、`docker-compose.yml`（项目根目录）、`.dockerignore`
- 复用：根目录 `vector-database.yml`（Milvus 编排思路：etcd + minio + standalone + attu）
- 修改：`src/main/resources/application.yml.example`（补 P2 新增环境变量样例）

**具体改动**

1. **Dockerfile**（多阶段构建）：
   ```dockerfile
   # ---- 构建层：依赖缓存 ----
   FROM maven:3.9-eclipse-temurin-17 AS build
   WORKDIR /build
   COPY pom.xml .
   RUN mvn -B dependency:go-offline          # 依赖层缓存，pom 未变时不重复下载
   COPY src ./src
   RUN mvn -B package -DskipTests

   # ---- 运行层 ----
   FROM eclipse-temurin:17-jre
   RUN useradd -r -u 1001 sba                 # 非 root 运行
   USER sba
   WORKDIR /app
   COPY --from=build /build/target/*.jar app.jar
   EXPOSE 9900
   ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
   ```
2. **docker-compose.yml**：
   - `app` 服务：build 上面的 Dockerfile；`depends_on: milvus(healthy), redis(healthy)`；环境变量：`DASHSCOPE_API_KEY`、`MCP_TENCENT_CLS_SSE_ENDPOINT`、`APP_API_KEY`、`SECURITY_ALLOWED_ORIGINS`、`REDIS_HOST/REDIS_PASSWORD`、`MILVUS_COLLECTION_NAME`（全部沿用 P0/P2 环境变量命名，密钥类无默认值，缺失容器启动即失败）；
   - `redis: redis:7-alpine`（`--requirepass ${REDIS_PASSWORD}`，healthcheck `redis-cli ping`）；
   - Milvus 编排复用 `vector-database.yml` 的 etcd/minio/standalone/attu 结构（compose `include` 引入或整段并入）；**镜像建议**由 `milvusdb/milvus:v2.5.10` 升至 `v2.6.x` 与 SDK `milvus-sdk-java:2.6.10`（`pom.xml` 第 93-96 行）对齐，T6 的标量 inverted index 在 2.6 上行为最佳（升级前先在 6.1 环境回归）；
   - uploads 目录挂 volume 持久化（`./volumes/app-uploads:/app/uploads`）。
3. `.dockerignore`：`target/`、`uploads/`、`volumes/`、`.git` 等。

**验收标准**
- `docker compose up --build` 一键拉起 app + redis + milvus，`/milvus/health` 200（带 X-API-Key）；
- 容器以非 root 用户运行（`docker exec ... id -u` = 1001）；
- 任意密钥未注入时容器启动失败并打印缺失键名（fail-fast）；全部密钥仅存在于环境变量/compose env_file（不入镜像层，`docker history` 无密钥）；
- 应用容器重启后会话（Redis）与索引数据（Milvus volume、uploads volume）不丢失；
- 内网无外网环境下构建可用（基础镜像预先导入，或提供镜像仓库地址说明）。

---

## 4. 关键技术决策

### D1 索引异步化执行模型：进程内有界线程池 vs 消息队列 vs 保持同步
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 进程内有界线程池 + 内存/Redis 任务表（**选定**） | 零新增中间件；任务量级（单机、目录数百文件）完全够用；与 T2 批量化天然衔接；失败重试语义简单 | 重启丢队列中任务（Redis 任务表 + 重启扫 PENDING 补偿可缓解）；多实例需分布式锁 |
| B. MQ（RocketMQ/Kafka）解耦 | 削峰、持久化、跨实例消费 | 引入重量级组件，运维成本高；当前吞吐瓶颈在 DashScope 批量接口而非消费并行度，MQ 不解决瓶颈 |
| C. 保持同步（仅做批量化） | 改动最小 | 上传大文件仍阻塞 HTTP 线程数十秒（T2 后估算 17-33s），网关超时、用户体验差；失败语义无法观测（现状问题 #4 仍在） |

**选 A**：瓶颈在远程调用次数而非执行框架；任务表双写 Redis（`sba:index:task:{taskId}`）为多实例与 P3 留查询面。重启补偿：启动时扫内存/Redis 中 PENDING/RUNNING 任务重新入队（幂等：重跑前 `deleteBySource`）。

### D2 批量 embedding 分批大小：固定值 vs 配置化自适应
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 配置化 `dashscope.embedding.batch-size`（默认 10）（**选定**） | DashScope 各模型批量上限不同（text-embedding-v4 官方限制以文档为准），配置化可随配额调整不改代码；代码内 partition 自动切分 | 需文档核实默认值 |
| B. 硬编码上限 | 简单 | 上限调整（配额升级/换模型）必须改代码 |

**选 A**，默认 10 条/请求，实施时以 DashScope 官方文档核实并修正默认值；超过上限自动 partition，单批失败按批重试（T3 重试机制内），不影响已成功批次。

### D3 会话存储：内存 Map vs Redis 直存 vs Spring Session
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. `spring-boot-starter-data-redis` + 自定义 SessionStore（**选定**） | 引擎可控（key/TTL/降级策略全部自定义）；只存业务会话（`sba:session:{id}`），不被 Servlet Session 语义绑架；SSE + 前端自管 id 的现状下最贴合 | 需自写读写/降级逻辑（量小，约 150 行） |
| B. Spring Session（spring-session-data-redis） | 标准 HttpSession 透明外置 | 本项目的"会话"是业务多轮上下文（`SessionInfo` 自管消息窗口），不是 HttpSession；引入后还要绕开其 cookie/sessionId 语义，改造面反而更大 |
| C. 继续内存 | 零改动 | 无法多实例（目标 4 直接落空）；重启丢会话；泄漏风险（问题 #6） |

**选 A**，并用 `FallbackSessionStore` 装饰实现"Redis 不可用回退内存 + 告警"（需求明示的降级语义）。

### D4 新依赖最小集：Redis vs Caffeine vs 两者叠加
| 依赖 | 决定 | 理由 |
|---|---|---|
| `spring-boot-starter-data-redis` | **引入** | 会话外置 + embedding 缓存 + 任务表三个需求共用，一个客户端全解决；T5/T7/T3 三处收益 |
| Caffeine（本地缓存） | **不引入** | embedding 缓存的多实例命中率与一致性靠 Redis 已足够；文本 sha256 计算便宜（<1ms/KB 量级，估算），加本地一级缓存属于过度设计；若 P3 出现明显热点查询再评估 |
| `resilience4j-spring-boot3` + `resilience4j-micrometer` | **引入** | 限流/重试/超时/断路一体化，Spring Boot 3 生态标准，自带 Micrometer 指标（与 P1 可观测体系直接对接）；自研信号量需重造指标/退避/状态机轮子 |

### D5 度量类型迁移：在线改索引 vs 影子 collection 切换
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 新建 `biz_v2` + 全量重建 + 配置切换 + 旧表下线（**选定**） | Milvus 的 metric type 与 schema 建表后不可改，这是唯一可行路径；源文件都在 uploads 目录，"重建即回填"，无需数据导出管道 | 需要一次全量重索引（embedding 走 T7 缓存后二次成本很低）；切换窗口内新旧表并存占双份存储 |
| B. 保留 L2（归一化向量下排序等价，不改） | 零成本 | 分数语义错误（L2 越小越好）持续误导前端/LLM；未来换非归一化模型或加 rerank 时排序隐患爆发；技术债滚入 P3 |

**选 A**：成本一次性的且可控（重建任务本身就是 T3 交付物），长期正确性优先。

### D6 `_source` 过滤：JSON 表达式 vs 标量字段 + 标量索引
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 标量字段 `source` + inverted 标量索引（**选定**，P0 D4-B 既定方向） | 删除走索引命中（大表下估算从全表扫秒级降到毫秒级——估算）；表达式简单、转义面小；可扩展按文件名/扩展名过滤 | 需重建 collection（与 D5 合并成一次 `biz_v2` 迁移，边际成本趋近零） |
| B. 保留 `metadata["_source"]` JSON 表达式 | 不动 schema | Milvus JSON 字段过滤不利用索引（全表扫），数据量增长后每次上传的"删旧"越来越慢；JSON 路径转义规则复杂，注入面大 |

**选 A**，且与 D5 合并为同一次换表迁移（`biz_v2` 同时落 COSINE + source 字段），避免两次全量重建。`metadata` JSON 保留以兼容存量消费方，双写过渡。

### D7 embedding 缓存键：全文哈希 vs 分片内容+参数组合键
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. `sba:cache:embedding:{sha256(text)}`（**选定**） | 键短且定长；同文本跨模型版本命中需注意（用 `text` 即分片内容本身做键，重建时重叠分片/相同分片天然命中） | 未包含模型名/维度，换模型后需清空缓存（T6 迁移时显式 FLUSH 前缀 `sba:cache:embedding:*`） |
| B. `sha256(model + dim + text)` | 换模型安全 | 键计算多两字段，实际换模型是极低频事件且 T6 迁移流程里本来就要清缓存 |

**选 A** + 迁移手册中写明"换 embedding 模型必须清 embedding 缓存前缀"，把正确性放进流程而不是键设计。

### D8 限流实现：Resilience4j vs 自研信号量 vs 网关层
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. Resilience4j（**选定**） | 声明式配置、Retry/TimeLimiter/RateLimiter/CircuitBreaker 组合；micrometer 模块指标开箱即用；社区与 Spring Boot 3 集成成熟 | 新增 2 个依赖（轻量，无传递性大包） |
| B. 自研 `Semaphore` | 零依赖 | 指标、退避、并发窗口刷新全部手写，测试面大；P1 测试底座要为它单独补单测 |
| C. Nginx/网关限流 | 应用零改动 | 网关只看 QPS 看不到"embedding vs llm"业务维度；基础设施层交付物不在本仓库范围（P0 D2 同理） |

**选 A**；配置仅开 RateLimiter/Retry/TimeLimiter 三个实例（见 T7），断路器暂不启用（DashScope 短暂失败由重试+任务重试覆盖，避免误熔断拖垮上传链路）。

### D9 容器化：单容器全家桶 vs compose 编排 vs K8s
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. Dockerfile（多阶段）+ docker-compose 编排 app/redis/milvus（**选定**） | 复用根目录 `vector-database.yml` 成熟编排；一键起全环境；密钥全走环境变量（与 P0 5.1 一致） | 不具备 K8s 级自愈/伸缩 |
| B. K8s + Helm | 生产级编排 | 当前交付形态（企业内网单机/小集群）不需要；P3 视部署形态再引入 |
| C. 单容器装全部 | 部署物最少 | Milvus 依赖 etcd/minio，单容器不可行 |

**选 A**；compose 中 Milvus 镜像升 2.6.x 与 SDK 对齐（升级风险见第 7 节 #8）。

## 5. 与其它阶段的依赖关系

### 5.1 沿用 P0（`docs/plans/P0-security.md` 第 5 节）约定情况

| P0 约定 | P2 沿用方式 |
|---|---|
| 敏感配置无默认值外置（`DASHSCOPE_API_KEY`、`MCP_TENCENT_CLS_SSE_ENDPOINT`、`APP_API_KEY`、`security.allowed-origins`） | 原样沿用；P2 新增 `REDIS_PASSWORD`（无默认值）、`REDIS_HOST/REDIS_PORT`（非敏感，可留默认）同样遵守，缺失 fail-fast |
| `X-API-Key` 鉴权，`/api/**`、`/milvus/**` 强制，静态资源免鉴权 | P2 新增接口全部挂 `/api/**`（`/api/index/tasks*`），自动纳入过滤器，无需额外配置 |
| 统一响应体：成功 `{"code":0,"message":...,"data":<对象>}`；错误 HTTP 4xx/5xx + `{"code":<HTTP码>,"message":<脱敏文本>,"data":null}` | 任务 API、上传接口改造、503/429 拒绝响应全部遵循；任务 `failReason` 为脱敏文本（不含堆栈、密钥、向量） |
| JSON 一律 Jackson，禁止 `String.format` 拼 JSON | 异步任务的错误消息/任务详情序列化、Redis 会话与缓存 value 全部 Jackson |
| 日志不得打印密钥/完整向量 | 任务失败日志、Redis 降级日志、缓存日志只打 taskId/键名/维度数 |
| Milvus 表达式 `escapeExprString()` 转义 + `SAFE_FILENAME_REGEX` 白名单 | `VectorRepository.deleteBySource`（T6 改标量字段后）继续双保险 |
| 文件落盘三重校验（剥离路径+白名单+startsWith） | T3 异步任务的 `target` 路径来自已净化的落盘结果，不经二次用户输入；目录重建的 `directoryPath` 入参仅允许配置的 uploadPath（拒绝任意目录，防越权重建） |

### 5.2 沿用 P1（工程化底座）约定情况

| P1 约定 | P2 沿用方式 |
|---|---|
| 指标前缀 `sba.`（`sba.llm.calls/latency/tokens`、`sba.embedding.calls/latency`、`sba.milvus.ops/latency`、`sba.sse.sessions.active`） | 原样沿用；P2 新增指标同前缀：`sba.index.task.active`、`sba.index.chunks.total`、`sba.redis.ops/latency`、`sba.redis.fallback`、`sba.cache.embedding.hit/miss` |
| MDC traceId | 任务异步执行链路把 traceId 透传到任务线程（TaskDecorator），任务日志可追踪 |
| `BizException` + `GlobalExceptionHandler` | 文件超限（400）、队列满（503）、限流（429）等新错误路径全部走该体系，响应体形状与 P0 5.2 一致 |
| JUnit 5 + Mockito 测试底座 | T1/T2/T3/T4/T5/T7 的验收单测全部基于该底座（Repository 打桩 MilvusServiceClient、SessionStore 打桩 StringRedisTemplate、EmbeddingCache 命中/miss 用例） |
| 公共类落点 `org.example.common` / `org.example.util.FilenameSanitizer` | `SessionStore` 系列落 `org.example.common.session`；文件名净化复用 `FilenameSanitizer`；新增业务分层 `org.example.repository`、`org.example.task` |

### 5.3 为 P3 留的接口（跨阶段横向约定，P3 方案必须原样沿用）

1. **索引任务 API**（P3 管理界面的"索引状态/重建"页面直接对接）：
   - `POST /api/index/tasks`——触发索引任务（body：`{"directoryPath": "<可选，默认上传目录>"}` 或 `{"taskId": "<重试既有任务>"}`），返回 `{"code":0,"message":"success","data":{"taskId":"<uuid>"}}`；
   - `GET /api/index/tasks/{taskId}`——返回 `{"code":0,"data":{"taskId":...,"type":"FILE|DIRECTORY_REBUILD","target":...,"status":"PENDING|RUNNING|SUCCESS|FAILED","progress":"<已完成分片>/<总分片>","failReason":"<脱敏文本|null>","retryCount":n,"createTime":...,"startTime":...,"endTime":...}}`；
   - `GET /api/index/tasks`——任务列表（最近 50 条，倒序）。
   - 状态枚举固定四种：`PENDING / RUNNING / SUCCESS / FAILED`；失败重试不改 taskId，`retryCount` 递增。
2. **Redis key 统一前缀 `sba:`**（P3 任何新增缓存/状态 key 必须沿用）：
   - `sba:session:{sessionId}`——会话多轮上下文（TTL 滑动）；
   - `sba:index:task:{taskId}`——任务详情（TTL 24h）；
   - `sba:cache:embedding:{sha256(text)}`——embedding 结果缓存（TTL 可配）。
3. **Repository 落点**：`org.example.repository.VectorRepository`，能力面 `insertBatch / deleteBySource / search / loadCollectionOnce`；P3 管理界面（按文件查看/删除/重建索引）直接复用，不得在 Controller 再拼 gRPC 参数。
4. **新增接口全部走 `/api/**`**——自动纳入 `X-API-Key` 鉴权与统一响应体，P3 管理接口（如 `/api/admin/**`）同样遵守。
5. **上传接口响应扩展**：`data` 中新增 `taskId` 与 `indexed` 字段（`indexed` 在异步化后恒为 `false`，保留字段是为了 P3 之前的前端兼容与语义显式化）。
6. **collection 切换能力**：`milvus.collection-name`（环境变量 `MILVUS_COLLECTION_NAME`，默认 `biz`）——P3 做"知识库分 collection / 多库"时按此模式扩展。

## 6. 验证方案

### 6.1 压测与对比方法

**基准数据集**：准备 3 组——A：10 个小文件（<10KB）；B：10 个中文 Markdown（各 200-500KB，约 350-400 分片/个）；C：1 个大文件（5MB，接近 `index.max-file-size` 上限）。所有对比测试在同一数据集、同一 DashScope 配额、同一 Milvus 实例上各跑 3 次取中位数。

| 链路 | 方法 | 关注 |
|---|---|---|
| 索引链路（T2 前后对比） | 改造前后分别对数据集 B 逐个触发索引（改造前同步接口计时 / 改造后以任务 `startTime→endTime` 计时） | 单文件索引总耗时；`sba.embedding.calls` 次数（应降一个数量级）；`sba.milvus.ops` 次数（loadCollection 400→1） |
| 上传接口（T3 前后对比） | `curl -w '%{time_total}'` 上传数据集 C | 响应耗时应 <1s（原为同步索引耗时） |
| 检索链路（T6 前后对比） | 用 JMeter/wrk 直接压 `InternalDocsTools` 底层依赖的 `VectorSearchService.searchSimilarDocuments`（包一个仅鉴权的压测端点，或对 `/api/chat` 用 mock LLM——避免压测烧真实 token）；100 并发 × 60s | QPS、P95/P99（`sba.milvus.ops` latency）；L2 vs COSINE top-3 召回一致性 diff |
| SSE 并发（T4） | 并发 150 路 `/api/chat_stream`（问题固定短句，mock 或真实 LLM 限量） | 503 出现率（队列满）；`sba.sse.sessions.active` 峰值；线程数（jstack）无失控；`StringBuffer` 输出完整性（比对每路最终 fullAnswer 与流内容拼接一致） |
| 删除链路（T6） | 对数据集 B 重建后，计时按 source 删除单文件（旧表 JSON 表达式 vs 新表标量字段） | 删除耗时对比（估算大表下数量级差异） |
| 降级演练（T5） | 压测中途 `docker stop redis` | 功能不中断、`sba.redis.fallback` 告警、恢复后切回 |
| 缓存有效性（T7） | 同一目录两次定时重建 | 第二次 `sba.embedding.calls` 降幅（估算 >90%）与 `sba.cache.embedding.hit` 增长 |

### 6.2 指标观察（基于 P1 Micrometer 体系）

- 索引：`sba.index.task.active`（运行中任务数）、`sba.index.chunks.total`（累计分片写入）、`sba.embedding.calls` / `sba.embedding.latency`（批量后单"次"含一批文本，观察时注意口径变化）；
- 存储：`sba.milvus.ops` / `sba.milvus.ops.latency`（区分 insert/delete/search tag——P1 若未分 tag，随 T1 落地补 tag）、`sba.redis.ops` / `sba.redis.latency`、`sba.redis.fallback`（>0 立即告警）；
- 缓存与限流：`sba.cache.embedding.hit/miss`（命中率 = hit/(hit+miss)）、Resilience4j 内建指标（`resilience4j.ratelimiter.*.available.permits`、`resilience4j.retry.*.failed.calls`）；
- 会话：`sba.sse.sessions.active` 峰值 vs 线程池配置（16 线程 + 100 队列）校准容量。

### 6.3 上线前检查清单（发布门禁）

- [ ] 批量化索引回归：改造前后 chunk 数、id 规则、metadata 内容比对一致（T2）
- [ ] 同文档索引耗时对比报告归档，实测降幅达标（T2）
- [ ] 上传→任务查询→失败重试全链路演示通过；任务 API 无 Key 访问 401（T3）
- [ ] 定时重建 cron 在生产配置显式设置（或显式禁用 `-`），执行过至少一次演练（T3）
- [ ] 150 并发 SSE 演练：无 OOM、线程数受控、池满返回 503 统一错误体（T4）
- [ ] sessionId 回传后多轮续接手工验证（前端不带 Id 首轮 + 带 Id 次轮）（T4）
- [ ] Redis 拉起/停掉/恢复三态演练通过；`REDIS_PASSWORD` 无默认值且已注入；启动 fail-fast 验证（T5）
- [ ] `biz_v2` 迁移四步操作记录归档；新旧表召回一致性 diff 通过；切换后 24h 观察（T6）
- [ ] 大文件（>10MB）上传被拒且任务 FAILED 语义正确（T7）
- [ ] embedding 缓存与限流指标在监控面板可见；限流阈值与 DashScope 配额对齐（T7）
- [ ] `docker compose up` 一键部署演练通过；镜像层无密钥；非 root 运行（T8）
- [ ] 本方案 5.3 节横向约定已同步至 P3 方案文档

## 7. 风险与应对

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | DashScope 批量接口实际限制与配置默认值不符（批量上限/QPS 配额） | 批量化后反而大量 429，索引更慢 | 默认值保守（10 条/批、4 QPS）；上线前用 6.1 数据集 B 校准；配置化可在线调整（D2）；Resilience4j Retry 兜底 |
| 2 | 异步化改变对外语义，存量调用方（脚本/前端）假设"返回即索引完成" | 检索不到刚上传的内容被当 bug | 响应保留 `indexed` 字段显式语义；前端同批发布轮询逻辑（T3 第 5 点）；对外接口文档同步标注破坏性变更说明 |
| 3 | 重启丢队列中任务 | 索引静默缺失 | 任务表写 Redis（`sba:index:task:*`）；启动补偿：扫 PENDING/RUNNING 重新入队；重跑前 `deleteBySource` 保证幂等 |
| 4 | 定时重建与手动上传并发，同文件索引互相覆盖 | 数据不一致（重建删旧与上传写新交错） | 索引执行器单线程（D1），任务天然串行；同 target 去重（在队任务存在同 target 时合并） |
| 5 | 多实例部署时 `@Scheduled` 重复触发、内存会话与任务表不一致 | 重复索引/费用翻倍 | P2 以单实例为主；文档明示多实例前置条件：Redis SETNX 锁（`sba:index:rebuild-lock`）+ 会话/任务全走 Redis（T5/T3 已备） |
| 6 | COSINE 分数语义反转（越大越相似）被消费方误读 | 前端展示/LLM 工具结果错乱 | T6 验收项显式含"score 消费方确认"（`InternalDocsTools` 返回、前端展示）；迁移 PR 中统一排查 `.getScore()` 使用点 |
| 7 | `biz_v2` 迁移期间旧表残留脏数据或切换失败 | 检索质量下降 | 切换前旧表只读不动；保留回退开关（`milvus.collection-name` 改回 `biz` 即回退）；旧表下线前观察 ≥3 天 + query 备份 |
| 8 | Milvus 镜像 v2.5.10→v2.6.x 升级引入行为差异 | 部署链路回归风险 | 升级单独排期在 T8 演练环境先行；SDK 2.6.10 与 server 2.6 官方兼容矩阵确认；不通过则暂留 2.5.10（标量索引特性在 2.5 亦可用，仅 inverted 细节差异需验证） |
| 9 | Redis 成为新故障面（网络抖动引发会话抖动） | 多轮续接偶发失效 | FallbackSessionStore 内存降级 + 自动探活恢复；`sba.redis.fallback` 告警阈值（>0 即告警）；降级期间会话有损语义在日志/文档明示 |
| 10 | 有界线程池容量误判（SSE 长任务 + ai_ops 10 分钟任务占满） | 正常对话被 503 | 线程池参数配置化；`sba.sse.sessions.active` 与队列水位（`ThreadPoolTaskExecutor` 指标）监控校准；ai_ops 与 chat_stream 可按需拆分两个池（预留 `sbe.executor.aiops.*` 配置） |
| 11 | embedding 缓存脏数据（换模型后旧向量命中） | 召回错乱 | D7 决策：换 embedding 模型流程强制清 `sba:cache:embedding:*` 前缀；写入 6.3 检查清单 |
| 12 | Gson 静态化后误以为 Gson 全局线程安全的边界（`Gson` 实例线程安全但自定义 TypeAdapter 不一定） | 极端场景序列化错乱 | 仅用默认 `new Gson()` 无自定义适配器；Repository 单测覆盖并发 insertBatch |
