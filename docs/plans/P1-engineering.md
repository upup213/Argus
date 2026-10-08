# P1 阶段方案：工程化底座

> 适用工程：`d:\代码\SuperBizAgent-release-2026-05-17`（Maven 单模块，Java 17 + Spring Boot 3.2.0 + Spring AI Alibaba 1.1.0.0-RC2 + DashScope（通义）+ Milvus 2.6.x，入口 `org.example.Main`，前端为 `src/main/resources/static` 下原生 HTML/JS）。
> 前置：P0（安全止血，见 `docs/plans/P0-security.md`）已定约定；本方案第 5 节声明沿用情况，并与 P2（`docs/plans/P2-performance.md`）横向约定对齐。
> 文中所有路径、行号、方法名均基于当前代码快照逐项核实；行号在后续修改后会漂移，以"文件 + 方法/代码特征"为准。本方案不写时间估计（不写人日/工期），以优先级 + 任务顺序驱动实施。

## 1. 背景与目标

### 1.1 背景

P0 完成了安全止血后，服务可以"安全地坏"，但还谈不上"安全地持续演进"——当前工程在工程化底座上存在系统性缺口。经逐项核查源码，现状如下：

| # | 问题 | 核实位置（当前快照） |
|---|------|----------------------|
| 1 | 无测试底座：`pom.xml` 未引入 `spring-boot-starter-test`；`src/test/java` 下仅 `Outer.java`、`TestMain.java` 两个 JVM 类加载实验（非测试），`target/test-classes` 只编译了这两者 | `pom.xml` 第 72-147 行（无 test 依赖）；`src/test/java/Outer.java`、`src/test/java/TestMain.java` |
| 2 | 无 CI、无静态检查、项目未初始化 Git（无 `.git`，无远程），`Makefile` 仅覆盖"运行/上传"不含 build+test+门禁 | 根目录 `Makefile`（第 42-239 行均为运行/上传编排，无 test 目标）；`pom.xml` 无 jacoco/checkstyle 插件 |
| 3 | 无统一异常与响应：`ChatController` 业务失败返回 HTTP 200 + `ApiResponse.success` 包裹 error；`ApiResponse` 在两处重复定义且 `code` 语义与 P0 约定冲突 | `ChatController.java` 第 107-110 行（`return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(...)))`）；`ApiResponse` 定义于 `ChatController.java` 第 606-628 行、`FileUploadController.java` 第 108-136 行 |
| 4 | 无参数校验：`ChatRequest` 无长度限制、`VectorSearchService` 的 `topK` 无上限、`QueryLogsTools` 的 `VALID_REGIONS` 定义后未接入 | `ChatController.java` 第 510-521 行（`ChatRequest`）；`VectorSearchService.java` 第 42-59 行（`searchSimilarDocuments` 直接 `withTopK(topK)`）；`QueryLogsTools.java` 第 144-146 行（`VALID_REGIONS`）、第 161-165 行（`queryLogs` 未校验 `region`） |
| 5 | 无可观测性：无 Micrometer/Actuator、无 MDC traceId；LLM/embedding/Milvus/SSE 调用全黑盒 | `pom.xml` 无 actuator；`ChatService.java` 第 173-179 行、`AiOpsService.java` 第 51-71 行、`VectorEmbeddingService.java` 第 76-120 行、第 152-212 行（均为裸调用无指标） |
| 6 | 依赖治理混乱：Jackson 2.17.0 手动锁定、Gson 与 Jackson 双栈并存、lombok 手动锁版、devtools 无必要、编译器用 source/target 而非 release | `pom.xml` 第 49-68 行（jackson 4 项锁定）、第 86-91 行（devtools）、第 102-106 行（gson 2.10.1）、第 121-127 行（lombok 1.18.30）、第 161-176 行（compiler 插件 source/target 17） |
| 7 | 代码重复与死代码：`buildMethodToolsArray` 两处重复、`buildErrorResponse` 三处重复、多处魔法值、若干未接线类/常量 | `ChatService.java` 第 123-131 行与 `AiOpsService.java` 第 131-139 行；`QueryLogsTools.java` 第 592-601 行、`QueryMetricsTools.java` 第 223-233 行、`InternalDocsTools.java` 第 73-77 行；`dto/AIOpsRequest.java`、`tool/DropCollection.java`、各 `TOOL_*` 常量、`MilvusConstants.MILVUS_DB_NAME`、`MilvusProperties.database` |

### 1.2 目标

1. **测试底座**：引入 JUnit 5 + Mockito，补齐核心纯函数单测，覆盖率达到"核心逻辑 70%+"，并建立测试命名/目录规范。
2. **CI 门禁**：本地脚本优先，提供 build + test + 静态检查（含 P0 要求的密钥明文 grep 门禁），可选 GitHub Actions 模板。
3. **可观测性**：Micrometer + Actuator 落地 LLM/embedding/Milvus/SSE 四类指标与 MDC traceId 过滤器。
4. **统一异常与响应**：单一 `common.ApiResponse` + `BizException` + 错误码枚举 + 全局异常处理器，错误体严格遵循 P0 约定。
5. **参数校验**：`@Valid` + 约束注解、region 白名单真正接入、`topK` 收敛到 1..20。
6. **依赖治理**：Spring Boot 升到 3.2.x 最新 patch、Jackson 交回 BOM、移除 devtools、Gson/Jackson 收敛、lombok 交 BOM、编译器改 `release 17`。
7. **代码整备**：消除重复方法与魔法值、清理死代码、处置 `WebConfig` 自定义 `ObjectMapper` Bean。

技术选型与现有栈一致（Java 17、Spring Boot 3.2.x、Maven、Jackson、SLF4J、JUnit 5 + Mockito）；新增依赖仅 4 项且逐一说明理由（见 4.4）。

## 2. 范围界定

### 2.1 本期做什么

| 任务 | 内容 | 优先级 |
|---|---|---|
| T1 | 测试体系：引入测试依赖与 JaCoCo，删实验类，补核心纯函数单测 + Service 层 Mockito 单测 | P1-高 |
| T2 | 依赖治理：pom.xml 版本收敛与插件调整（先做，避免后续任务反复动构建） | P1-高 |
| T3 | 统一异常与响应：`common.ApiResponse` / `BizException` / 错误码枚举 / `GlobalExceptionHandler` | P1-高 |
| T4 | 参数校验：`spring-boot-starter-validation` + 约束注解 + region/topK 收敛 | P1-高 |
| T5 | 可观测性：Actuator + Micrometer 指标 + MDC traceId 过滤器 | P1-中 |
| T6 | 代码整备：重复方法抽取、魔法值常量化、死代码清理、ObjectMapper Bean 处置 | P1-中 |
| T7 | CI：本地脚本 + 可选 GitHub Actions 模板 + 密钥门禁 | P1-中 |

### 2.2 明确不做什么（留待后续阶段）

- 不做集成测试拉起真实 Milvus/DashScope（需要外置密钥与网络，成本高且不稳定；本阶段所有 Milvus/embedding 客户端一律 Mockito 打桩，见 D1）。
- 不做端到端 UI 自动化测试、不做前端测试框架（前端测试与重构属 P3）。
- 不引入 Testcontainers（Milvus 容器化测试属 P2 容器化之后的增强项）。
- 不做业务功能与提示词改动（`RagService.buildPrompt()`、`AiOpsService` 各 Prompt、`ChatService.buildSystemPrompt()` 的文案不动；仅 T4 对其中硬编码 `ap-guangzhou` 做常量引用替换，不改变语义）。
- 不做完整 Spring Security、用户体系、审计（P0 D2 已定，P3 再做）。
- 不做依赖漏洞扫描平台（OWASP Dependency-Check 等）、不做代码风格强制（checkstyle/spotless）——本阶段仅做"密钥明文 grep"一类轻量静态门禁，完整静态分析留待 P2 或视需要引入。
- 不做批量索引/异步索引/Redis 会话改造（P2 范围）；但本阶段公共类落点与指标命名必须为 P2 预留（见第 5 节）。

### 2.3 建议实施顺序（依赖驱动）

```
T2（依赖治理，pom 定型，其余任务的构建/测试都依赖它）
 → T3（统一异常，公共类 org.example.common 落点，后续参数校验/代码整备都引用它）
 → T1（测试底座，在 T3 公共类就位后补单测最省事）
 → T4（参数校验，依赖 T3 的 BizException + GlobalExceptionHandler）
 → T6（代码整备，抽取公共方法/清理死代码，依赖 T3 落点）
 → T5（可观测性，指标埋点可与 T6 合并 review）
 → T7（CI 收尾，此时 build+test+门禁全链路可跑通）
```

T1 与 T2 可并行启动（T1 的测试依赖可在 T2 同一次 pom 变更中落地）；T5/T6 改动面重叠，建议合并为同一次代码评审。

## 3. 任务清单

> 优先级定义：**P1-高**（底座性、被其它任务依赖，或直接决定代码能否安全演进）｜**P1-中**（增强性与一致性，不阻塞其它任务）。所有新增公共类落点遵循 `org.example.common`（ApiResponse/BizException/GlobalExceptionHandler 等）与 `org.example.util`（工具类），与 P2 约定一致。

---

### T1｜P1-高｜测试体系：JUnit 5 + Mockito 底座与核心纯函数单测

**目标**：建立可重复执行的测试底座，覆盖核心纯函数（分块、向量转换、扩展名校验、时长计算）与 Service 层失败分支；删掉非测试的实验类。

**涉及文件**
- `pom.xml`（新增 `spring-boot-starter-test`、`jacoco-maven-plugin`，理由见 4.4）
- 删除：`src/test/java/Outer.java`、`src/test/java/TestMain.java`（JVM 类加载实验，非测试）
- 新增测试（`src/test/java/org/example/...`，包结构镜像 `src/main`）：
  - `src/test/java/org/example/service/DocumentChunkServiceTest.java`
  - `src/test/java/org/example/service/VectorEmbeddingServiceTest.java`
  - `src/test/java/org/example/service/VectorIndexServiceTest.java`
  - `src/test/java/org/example/controller/FileUploadControllerTest.java`
  - `src/test/java/org/example/agent/tool/QueryMetricsToolsTest.java`

**具体改动**
1. **测试依赖**（`pom.xml` `<dependencies>` 内新增，scope=test，版本由 Spring Boot BOM 管理）：
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-test</artifactId>
       <scope>test</scope>
   </dependency>
   ```
   `spring-boot-starter-test` 已含 JUnit 5、Mockito、AssertJ、MockMvc；本阶段无 MockMvc 需求，但随 starter 一并可用。
2. **覆盖率插件**：`jacoco-maven-plugin`（`prepare-agent` + `report` 两个 execution，绑定到 `test`/`verify` 阶段），并配置 `check` 规则：核心包 `org.example.service.*`、`org.example.agent.tool.*` 指令覆盖率 ≥ 70%（配置 `org.example.constant`、`org.example.dto`、`org.example.config` 等纯数据结构/配置类为 exclude，避免噪音拉低门槛）。
3. **删除实验类**：`Outer.java`、`TestMain.java` 及其 `target/test-classes` 编译产物（`mvn clean` 即可清理）。
4. **核心纯函数单测**（纯函数 = 无外部依赖、可确定性断言）：
   - `DocumentChunkServiceTest`：针对 `chunkDocument(String, String)`（第 35 行起）做分块算法断言——①空/纯空白内容返回空列表（第 38-41 行）；②按 `splitByHeadings`（第 61 行）切出标题；③`chunkSection`（第 104 行）在内容超 `maxSize` 时按段落切分且带 overlap（第 132-150 行）；④`getOverlapText`（第 193 行）句子边界截断（第 203-209 行）。构造 `DocumentChunkConfig`（maxSize=800、overlap=100）直接注入 service 字段（反射或 package-private setter），不启动 Spring 容器。
   - `VectorEmbeddingServiceTest`：`getFloats(TextEmbeddingResult)`（第 123-144 行）目前为 `private static`，需将访问级别放宽为包级（`static List<Float> getFloats(...)`）以直接单测；断言 Double→Float 转换的精度截断行为（`value.floatValue()`）、空 result/null 输出抛异常分支（第 124-134 行）。另测 `calculateCosineSimilarity`（第 231-247 行）的维度不一致抛异常、正交向量=0、同向量=1。
   - `FileUploadControllerTest`：`getFileExtension`（第 138 行）与 `isAllowedExtension`（第 146 行）目前为 private 实例方法，依赖 `FileUploadConfig`。抽取为可测方法（见 T6 建议 `org.example.util.FilenameSanitizer` 或直接放宽为 package-private），断言 `txt/md` 白名单命中、`exe` 拒绝、无后缀返回空串、大小写归一（第 143 行 `toLowerCase`）。
   - `QueryMetricsToolsTest`：`calculateDuration(String)`（第 198-218 行）为 private，放宽为 package-private 后断言：>1h 输出 `%dh%dm%ds`、>1m 输出 `%dm%ds`、<1m 输出 `%ds`、非法时间串（catch 分支第 214-217 行）返回 `"unknown"`。
5. **Service 层 Mockito 单测**（mock 外部依赖，不拉起 Milvus/DashScope）：
   - `VectorIndexServiceTest`：mock `MilvusServiceClient`、`VectorEmbeddingService`、`DocumentChunkService`，覆盖 `indexSingleFile`（第 124-168 行）的失败分支——①文件不存在抛 `IllegalArgumentException`（第 128-130 行）；②分片循环内 `generateEmbedding` 抛异常时整体 `RuntimeException` 上抛（第 161-163 行）；③`deleteExistingData`（第 173-215 行）中 `loadCollection` 状态码 65535（已加载）被容忍、非 0 非 65535 时告警返回（第 192-196 行）——用 `R<RpcStatus>` mock 对象打桩 status 值断言分支。
6. **测试命名/目录规范**（写入约定，供 P2/P3 沿用）：
   - 目录：`src/test/java` 下包结构镜像 `src/main/java`（`org.example.service.XxxTest` 对应 `org.example.service.Xxx`）。
   - 命名：`{被测类名}Test`；测试方法 `{方法名}_{场景}_{期望}`（如 `chunkDocument_emptyContent_returnsEmptyList`）。
   - 断言：优先 AssertJ（`assertThat(...)`）；mock 优先 Mockito（`@ExtendWith(MockitoExtension.class)`）；禁用真实网络/容器（见 D1）。

**验收标准**
- `mvn test` 全绿；`mvn verify` 生成 JaCoCo 报告且 `org.example.service`/`org.example.agent.tool` 指令覆盖率 ≥ 70%。
- `src/test/java` 下无 `Outer.java`/`TestMain.java`；`target/test-classes` 无对应 `.class`。
- 新增测试数 ≥ 5 个类、≥ 20 个用例；所有用例不依赖真实 DashScope/Milvus/网络（离线可跑）。
- 纯函数单测覆盖上述 4 个纯函数的所有分支（含异常/边界分支）。

---

### T2｜P1-高｜依赖治理：版本收敛与构建插件调整

**目标**：消除手动锁版本与双栈并存，让 Spring Boot BOM 统一管理版本，降低升级与 CVE 修补成本。

**涉及文件**
- `pom.xml`（第 7-12 行 parent、第 18-25 行 properties、第 26-70 行 dependencyManagement、第 72-147 行 dependencies、第 150-178 行 build）

**具体改动**
1. **Spring Boot 3.2.0 → 3.2.x 最新 patch**（`pom.xml` 第 10 行 `parent.version`）：改为 3.2 系列最新 patch 版本（实施时查 Maven Central 确定精确号，**不跨到 3.3+ 小版本**，避免与 Spring AI Alibaba 1.1.0.0-RC2 的兼容性风险）。理由：3.2.0 起 3.2.x 各 patch 修复了多个 CVE 与 bug，且 patch 升级零 API 变更。
2. **Jackson 手动锁定交回 BOM**：删除 `dependencyManagement` 第 49-68 行的 4 个 Jackson artifact（`jackson-core`/`jackson-databind`/`jackson-annotations`/`jackson-datatype-jsr310` 的 `2.17.0` 显式版本），改由 Spring Boot 3.2.x BOM 统一管理。理由：手动锁定 2.17.0 已脱离 Spring Boot 3.2 的 BOM 版本（3.2 管理 2.15.x），长期会造成版本漂移与重复类风险；删除后如有传递依赖需要更高版本，由 BOM 冲突仲裁，实施时用 `mvn dependency:tree` 验证无 `jackson` 版本冲突。
3. **移除 `spring-boot-devtools`**（第 86-91 行）：生产无益（会禁用模板缓存、触发重启），且与 SSE 长连接场景存在已知干扰（重启钩子可中断流式响应）。开发期热部署由 IDE 提供。
4. **Gson/Jackson 双栈收敛**：Gson 当前仅用于 `VectorIndexService.insertToMilvus` 第 286 行构建 Milvus metadata 的 `JsonObject`。方案（二选一，推荐 A）：
   - **A（推荐）**：保留 Gson 但明确收敛到"Milvus metadata 边界"，删除 `VectorIndexService` 内的 `new com.google.gson.Gson()`（第 286 行）改为注入/静态复用，并在类注释标注"Gson 仅用于 Milvus SDK 的 JSON metadata 字段，业务 JSON 一律 Jackson"。理由：P2 的 `VectorRepository`（`org.example.repository`）已计划静态复用 `Gson`（P2 T1），此方向与 P2 一致，避免本阶段引入 Jackson `JsonNode` 与 Milvus SDK 的兼容性验证成本。
   - B：替换为 Jackson `ObjectNode`（`objectMapper.valueToTree(metadata)`）后序列化为 JSON 字符串传给 `InsertParam.Field`。理由：彻底单栈。风险：需验证 Milvus SDK 2.6.10 对 JSON 字段接受 Jackson 节点/字符串的行为，测试成本高。
   - 本阶段选 A，`pom.xml` 的 gson 依赖（第 102-106 行）保留（P2 继续用）。
5. **lombok 交 BOM**：删除 `dependencies` 中 lombok 的显式版本（第 125 行 `1.18.30`），由 Spring Boot BOM 管理；`maven-compiler-plugin` 的 `annotationProcessorPaths`（第 168-174 行）中 lombok 版本改为 `${lombok.version}`（Spring Boot 父 POM 已定义该属性，值为 1.18.30）。理由：annotationProcessorPaths 不能继承 BOM 版本，但可引用父 POM 属性，实现单一版本来源。
6. **编译器改 `release 17`**：删除 `properties` 第 19-20 行的 `maven.compiler.source/target` 冗余与 `maven-compiler-plugin` 配置（第 165-167 行）中的 `source`/`target`，统一在 `properties` 增加 `<java.version>17</java.version>`，并让插件使用 `<release>${java.version}</release>`（或直接依赖 Spring Boot 父 POM 的 `java.version` 约定，删除本插件配置只保留 `annotationProcessorPaths`）。理由：`release` 相较 `source/target` 能约束 API 级别（防止误用更高版本 JDK API），是 Spring Boot 3 的标准做法。

**验收标准**
- `mvn -v` 使用 JDK 17；`mvn clean package` 成功，`target/*.jar` 可正常 `java -jar` 启动（含 P0 的环境变量）。
- `mvn dependency:tree` 中 jackson 版本由 BOM 统一（无 2.17.0 残留）；lombok 版本来自 BOM；gson 仅出现一次且版本 2.10.1。
- `pom.xml` 无 `spring-boot-devtools`、无 jackson/lombok 显式版本号；`maven-compiler-plugin` 使用 `release`（或删除冗余 source/target）。
- 编译产物（class 文件 major version 61 = Java 17）无变化；应用功能回归通过（见 6.2）。

---

### T3｜P1-高｜统一异常与响应：common.ApiResponse + BizException + 全局异常处理器

**目标**：消灭两处重复 `ApiResponse` 与"业务失败返回 HTTP 200"的语义混乱，错误体严格遵循 P0 约定，为 P2 的 503/429 等错误路径提供统一出口。

**涉及文件**
- 新增（允许）：
  - `src/main/java/org/example/common/ApiResponse.java`
  - `src/main/java/org/example/common/BizException.java`
  - `src/main/java/org/example/common/ErrorCode.java`（错误码枚举）
  - `src/main/java/org/example/common/GlobalExceptionHandler.java`（`@RestControllerAdvice`）
- 修改：
  - `src/main/java/org/example/controller/ChatController.java`（删除第 606-628 行内部 `ApiResponse`；第 65-111、116-137、379-399 行的异常处理改为抛 `BizException` 或返回统一 `ApiResponse`）
  - `src/main/java/org/example/controller/FileUploadController.java`（删除第 108-136 行内部 `ApiResponse`；改用统一类）
  - `src/main/java/org/example/controller/MilvusCheckController.java`（第 29-49 行返回裸 `Map`，改统一 `ApiResponse`，可选）

**具体改动**
1. **`common.ApiResponse<T>`**（形状严格对齐 P0 5.2：成功 `{"code":0,"message":...,"data":<对象>}`、失败 `{"code":<HTTP码>,"message":<脱敏文本>,"data":null}`）：
   ```java
   public class ApiResponse<T> {
       private int code;      // 成功=0；失败=HTTP 状态码
       private String message;
       private T data;
       public static <T> ApiResponse<T> success(T data) { /* code=0, message="success" */ }
       public static <T> ApiResponse<T> error(int code, String message) { /* data=null */ }
   }
   ```
   **关键差异点**：现有 `ChatController.ApiResponse.success` 设 `code=200`（第 613-618 行）、`error` 设 `code=500`（第 621-626 行），与 P0"成功 code=0"冲突，必须修正为 `success→code=0`、`error(code,msg)`。
2. **`ErrorCode` 枚举**：定义 `BAD_REQUEST(400)`、`UNAUTHORIZED(401)`、`NOT_FOUND(404)`、`INTERNAL_ERROR(500)`、`SERVICE_UNAVAILABLE(503)` 等，与 HTTP 语义一一对应（P0 已定 400/401/500，本阶段补充 404/503 供 P2 队列满/限流使用）；枚举值含默认脱敏 message。
3. **`BizException`**（`RuntimeException` 子类）：携带 `ErrorCode` + 可选 `message`；message 默认取自 ErrorCode，可被业务覆盖但**必须脱敏**（不含堆栈/密钥/向量，P0 5.3）。
4. **`GlobalExceptionHandler`**（`@RestControllerAdvice`）：
   ```java
   @ExceptionHandler(BizException.class)                    // → code=errCode, HTTP 同码
   @ExceptionHandler(MethodArgumentNotValidException.class) // 参数校验失败 → 400，取首条 fieldError 脱敏消息
   @ExceptionHandler(Exception.class)                       // 兜底 → 500 + "internal error"（脱敏，不泄露 e.getMessage()）
   ```
   兜底 `Exception` 处理器**不回显 `e.getMessage()`**（防泄密，与 P0 T8 兜底原则一致），原始异常由 `logger.error` 完整落日志。返回 `ResponseEntity<ApiResponse<Void>>`，HTTP 状态码与 `code` 字段一致。
5. **`ChatController` 改造**：
   - 删除内部 `ApiResponse`（第 606-628 行），`ChatResponse`（第 551-571 行）保留（它是业务负载，非响应外壳，与统一 `ApiResponse` 无冲突）。
   - 第 107-110 行 catch 中"业务失败 HTTP 200"改为：让 `GraphRunnerException`/`Exception` 走 `GlobalExceptionHandler` 抛 `BizException`，或显式返回 `ResponseEntity.status(500).body(ApiResponse.error(500, "对话失败"))`；保留 `ChatResponse.error` 仅用于 SSE `SseMessage.error`（SSE 是流内协议，不受 HTTP 状态码约束，见下）。
   - `clearChatHistory`/`getSessionInfo` 的"会话不存在/ID 为空"（第 121-131、391-393 行）改抛 `BizException(400/404)` 或返回对应 `ApiResponse.error`，不再用 `ApiResponse.error`（原 code=500 语义错误）。
   - **SSE 例外说明**：`/api/chat_stream`、`/api/ai_ops` 的 `SseEmitter` 一旦建立就无法改 HTTP 状态码，流内错误继续用 `SseMessage.error`（第 577-603 行），**不适用**统一错误体；但流建立前的请求校验（如 P0 鉴权、T4 参数校验）仍走统一 401/400。
6. **`FileUploadController` 改造**：删除内部 `ApiResponse`（第 108-136 行），`upload()` 改用 `common.ApiResponse`；第 96-102 行 IOException 分支改抛 `BizException(500)` 或返回 `ResponseEntity.status(500).body(ApiResponse.error(500, ...))`，非法文件名/格式（第 36-49 行）改抛 `BizException(400, ...)`。

**验收标准**
- 全工程仅 `org.example.common.ApiResponse` 一处定义（grep `class ApiResponse` 仅 1 处）。
- `curl /api/chat` 触发业务失败：HTTP 500 + `{"code":500,"message":<脱敏文本>,"data":null}`，不再出现 HTTP 200 包裹 error；参数非法返回 400、未鉴权返回 401（与 P0 一致）。
- 成功响应：`{"code":0,"message":"success","data":{...}}`（注意 code 从 200 改为 0，前端 `app.js` 若依赖 `code===200` 需同步核对，见第 7 节风险 #3）。
- 未知异常兜底返回 `{"code":500,"message":"internal error","data":null}`，日志含完整堆栈但响应体不含堆栈/密钥。
- `@RestControllerAdvice` 单测：mock 触发 `BizException`/`MethodArgumentNotValidException`/`Exception` 三类，断言响应体形状与 HTTP 码。

---

### T4｜P1-高｜参数校验：@Valid 约束 + region 白名单接入 + topK 收敛

**目标**：堵住无边界输入导致的资源滥用与语义错误（超长问题、超大 topK、非法 region）。

**涉及文件**
- `pom.xml`（新增 `spring-boot-starter-validation`，理由见 4.4）
- `src/main/java/org/example/controller/ChatController.java`（第 510-521 行 `ChatRequest` 加约束；第 64-65、143-144 行入参加 `@Valid`）
- `src/main/java/org/example/service/VectorSearchService.java`（第 42-59 行 `topK` 收敛）
- `src/main/java/org/example/agent/tool/QueryLogsTools.java`（第 144-146 行 `VALID_REGIONS` 真正接入 `queryLogs`）

**具体改动**
1. **`ChatRequest` 约束**（第 510-521 行）：
   ```java
   public static class ChatRequest {
       @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
       @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
       @Size(max = 64, message = "会话ID过长")
       private String Id;

       @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
       @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
       @NotBlank(message = "问题不能为空")
       @Size(max = 4096, message = "问题长度不能超过4096字符")
       private String Question;
   }
   ```
   `@NotBlank` 替换第 70-73 行的手工空串校验（可保留手工校验作双保险或删除）。`@Valid` 加在 `@PostMapping("/chat")`、`@PostMapping("/chat_stream")` 的 `@RequestBody` 前。
2. **`VectorSearchService.searchSimilarDocuments`（第 42-59 行）topK 收敛**：入口增加 `if (topK < 1 || topK > 20) throw new BizException(ErrorCode.BAD_REQUEST, "topK 必须在 1..20 之间")`。理由：topK 直接决定 Milvus 检索与返回负载，无上限可被滥用；1..20 覆盖当前业务（`rag.top-k` 默认 3、`InternalDocsTools.topK` 默认 3）。
3. **`QueryLogsTools` region 白名单真正接入**（第 144-146 行 `VALID_REGIONS` 目前死定义）：
   ```java
   private String resolveRegion(String region) {
       if (region == null || region.isBlank()) return DEFAULT_REGION;         // 第 148 行已有
       return VALID_REGIONS.contains(region.trim()) ? region.trim() : DEFAULT_REGION;
   }
   ```
   在 `queryLogs`（第 161-182 行）入口调用，非法 region 回退默认值并 `logger.warn`（不抛错，LLM 工具调用容错优先，避免一个错误 region 打断整个 Agent 流程）。同步将 `getAvailableLogTopics`（第 121-122 行）中硬编码的 `availableRegions`/`defaultRegion` 改为引用 `VALID_REGIONS`/`DEFAULT_REGION` 常量。

**验收标准**
- `POST /api/chat` 空 Question → HTTP 400 + 统一错误体；Question 超 4096 字符 → 400；Id 超 64 字符 → 400。
- `VectorSearchService.searchSimilarDocuments(query, 0)`、`(query, 21)` 抛 `BizException`（单测覆盖边界 1 与 20 恰好通过）。
- `QueryLogsTools.queryLogs(region="eu-west-1", ...)` 回退 `ap-guangzhou` 并告警；`region="ap-shanghai"` 正常；`getAvailableLogTopics` 输出与 `VALID_REGIONS` 常量一致。
- 校验失败响应体经 `GlobalExceptionHandler` 输出 `{"code":400,...}`，形状与 P0 一致。

---

### T5｜P1-中｜可观测性：Micrometer + Actuator + MDC traceId

**目标**：让 LLM/embedding/Milvus/SSE 四类关键调用可观测，请求链路可追踪（traceId），为 P2 压测与容量校准提供指标口径。

**涉及文件**
- `pom.xml`（新增 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`，理由见 4.4）
- `src/main/resources/application.yml`（新增 `management.endpoints.web.exposure.include`、`management.endpoint.health.show-details`）
- 新增（允许）：`src/main/java/org/example/common/TraceIdFilter.java`（`OncePerRequestFilter`）
- 埋点修改：
  - `src/main/java/org/example/service/ChatService.java`（第 53-57 行 `createDashScopeApi`、第 173-179 行 `executeChat`）
  - `src/main/java/org/example/service/AiOpsService.java`（第 51-71 行 `executeAiOpsAnalysis`）
  - `src/main/java/org/example/service/VectorEmbeddingService.java`（第 76-120 行 `generateEmbedding`、第 152-212 行 `generateEmbeddings`）
  - `src/main/java/org/example/controller/ChatController.java`（第 144、285 行 SSE 会话计数）

**具体改动**
1. **依赖与配置**：Actuator + Prometheus registry；`application.yml` 增加：
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,info,metrics,prometheus
     endpoint:
       health:
         show-details: never      # 健康详情不外泄（P0 脱敏精神）
   ```
   注：Actuator 端点默认无鉴权，P1 阶段建议将 `/actuator/**` 一并纳入 P0 的 `X-API-Key` 过滤器（或仅内网暴露），见第 7 节风险 #5。
2. **指标命名（前缀 `argus.`，与 P2 5.2 完全一致，不得另起）**：
   - `argus.llm.calls`（Counter，tag：`endpoint=chat|chat_stream|ai_ops`）、`argus.llm.latency`（Timer）、`argus.llm.tokens`（Counter，tag：`type=prompt|completion`，可选）。
   - `argus.embedding.calls`（Counter）、`argus.embedding.latency`（Timer）。
   - `argus.milvus.ops`（Counter，tag：`op=load|insert|delete|search`）、`argus.milvus.ops.latency`（Timer）。
   - `argus.sse.sessions.active`（Gauge）。
3. **埋点落点（已核实）**：
   - `ChatService.executeChat`（第 173-179 行）：在 `agent.call(question)`（第 175 行）前后取 `Timer.Sample` 记录 `argus.llm.latency` 与 `argus.llm.calls`；token 计数需从 `response.getMetadata()`（`Usage`）读取——当前 `executeChat` 返回 `String`（第 178 行 `response.getText()`）丢弃了 usage，需在方法内记录后再返回 text（不改公开签名），或记录为可选指标。
   - `AiOpsService.executeAiOpsAnalysis`（第 51-71 行）：在 `supervisorAgent.invoke(taskPrompt)`（第 70 行）外层记录 `argus.llm.calls`（tag=ai_ops）与 latency；token 若 `OverAllState` 不可得则省略，仅在 `argus.llm.calls` 计数。
   - `VectorEmbeddingService.generateEmbedding`（第 76 行 `textEmbedding.call(param)` 第 102 行）与 `generateEmbeddings`（第 152 行 `textEmbedding.call(param)` 第 175 行）：外层记录 `argus.embedding.calls`/`argus.embedding.latency`；批量接口单"次"含一批文本，口径在指标描述中注明（与 P2 6.2 口径一致）。
   - Milvus 操作：本阶段最小埋点在 `VectorIndexService`（`loadCollection` 第 186-190、259-263 行、`delete` 第 203 行、`insert` 第 297 行）与 `VectorSearchService.search`（第 62 行）外层记录 `argus.milvus.ops`；T1 抽取 Repository 前暂以 Service 层计时（P2 T1 落地 Repository 后下沉到 Repository 层，保持指标名不变）。
   - `ChatController`：`chatStream`（第 144 行）与 `aiOps`（第 285 行）维护 `AtomicInteger sseActiveSessions`，在 `executor.execute` 前 +1、`emitter.complete()`/`completeWithError()` 后 -1，通过 `Gauge` 暴露 `argus.sse.sessions.active`。
4. **MDC traceId 过滤器**（`common.TraceIdFilter`）：
   - 读取请求头 `X-Trace-Id`，若无则生成 UUID；写入 `MDC.put("traceId", ...)`，响应头回写 `X-Trace-Id`；`finally` 清理 MDC。
   - 注入 `MeterRegistry` 不需要；仅依赖 SLF4J MDC。日志 pattern 需在 `application.yml`/logback 配置中追加 `%X{traceId}`（当前无 logback 自定义文件，可加 `logback-spring.xml` 或依赖默认 pattern，实施时补 `logging.pattern.level` 含 traceId）。
   - 与 P2 对齐：P2 的异步索引任务需把 traceId 透传到任务线程（`TaskDecorator`），本阶段过滤器产出 traceId 供其复用，命名与 P2 5.2 一致。

**验收标准**
- `GET /actuator/prometheus`（带 `X-API-Key`）可见 `argus.llm.*`、`argus.embedding.*`、`argus.milvus.*`、`argus.sse.sessions.active` 四类指标。
- 跑一轮 `/api/chat` + 上传 + `/api/chat_stream`（mock 或真实），`argus.llm.calls`/`argus.embedding.calls`/`argus.milvus.ops` 计数递增且 latency 有值。
- 请求带/不带 `X-Trace-Id`：响应头回写 traceId，日志出现 `traceId` 字段；并发请求 traceId 不串号。
- SSE 会话建立时 `argus.sse.sessions.active` +1，完成/断开后归零（无泄漏）。
- 指标命名全部 `argus.` 前缀，与 P2 5.2 列表一致。

---

### T6｜P1-中｜代码整备：重复方法抽取、魔法值常量化、死代码清理

**目标**：消除复制粘贴与魔法值，清理未接线代码，收敛公共逻辑，降低维护成本。

**涉及文件**
- `src/main/java/org/example/service/ChatService.java`（第 123-131 行 `buildMethodToolsArray`）
- `src/main/java/org/example/service/AiOpsService.java`（第 131-139 行 `buildMethodToolsArray`）
- `src/main/java/org/example/agent/tool/QueryLogsTools.java`（第 592-601 行 `buildErrorResponse`；第 144-148 行常量）
- `src/main/java/org/example/agent/tool/QueryMetricsTools.java`（第 223-233 行 `buildErrorResponse`）
- `src/main/java/org/example/agent/tool/InternalDocsTools.java`（第 73-77 行错误分支；第 63-65 行无结果分支）
- `src/main/java/org/example/service/VectorIndexService.java`（第 193、265 行 65535）
- `src/main/java/org/example/controller/ChatController.java`（第 145、286 行超时；第 336 行 chunkSize）
- `src/main/java/org/example/config/WebConfig.java`（第 34-37 行 `objectMapper` Bean）
- 删除：`src/main/java/org/example/dto/AIOpsRequest.java`、`src/main/java/org/example/tool/DropCollection.java`

**具体改动**
1. **抽取重复 `buildMethodToolsArray`**（`ChatService` 第 123 行 / `AiOpsService` 第 131 行，逻辑完全一致）：
   - 方案 A（推荐）：抽到 `org.example.util.ToolRegistryHelper`（或 `ChatService` 公开静态方法），输入 4 个 tool 引用 + `queryLogsTools` 是否为空，输出 `Object[]`。`AiOpsService` 注入调用（消除 private 复制）。
   - 方案 B：`AiOpsService` 复用 `ChatService.buildMethodToolsArray()`（注入 `ChatService`）。理由：逻辑相同且依赖同一组 bean。
   - 选 B（更少新类）；如后续工具集扩展再抽 `ToolRegistryHelper`。
2. **统一三处 `buildErrorResponse` / 错误 JSON 构造**（P0 T8 已要求 Jackson 化，本阶段进一步去重）：
   - `QueryLogsTools.buildErrorResponse`（第 592-601 行，catch 第 599 行仍 `String.format`）、`QueryMetricsTools.buildErrorResponse`（第 223-233 行，第 231 行 `String.format`）、`InternalDocsTools.queryInternalDocs` 异常分支（第 73-77 行）与无结果分支（第 63-65 行裸 JSON 字面量）。
   - 统一为 Jackson 序列化 `Map.of("success", false, "message", <安全文案>)`（或各自的 Output POJO），**catch 兜底改为固定常量**（不含 `e.getMessage()` 插值，防止引号/换行破坏 JSON），原始错误走 `logger.error`。
   - 可选：抽 `org.example.util.JsonUtil` 提供 `toJson(Object)`/`errorJson(String)`，供三个工具类共用（与 P0 5.3"JSON 一律 Jackson"一致）。
3. **魔法值常量化**：
   - `VectorIndexService` 第 193、265 行的 `65535`（Milvus "already loaded" 状态码）→ 常量 `MILVUS_LOADED_STATUS = 65535`（放 `MilvusConstants` 或 `VectorIndexService` 私有常量），注释说明语义。
   - `ChatController` 第 145 行 `300000L`（chat_stream 5 分钟超时）、第 286 行 `600000L`（ai_ops 10 分钟超时）→ 常量 `CHAT_STREAM_TIMEOUT_MS`、`AI_OPS_TIMEOUT_MS`（可进一步配置化为 `sbe.sse.*`，但配置化属 P2 T4，本阶段仅常量化）。
   - `ChatController` 第 336 行 `int chunkSize = 50`（报告分块）→ 常量 `REPORT_CHUNK_SIZE = 50`。
   - 硬编码 `ap-guangzhou`：`QueryLogsTools` 已用 `DEFAULT_REGION`（第 148 行）+ `VALID_REGIONS`（第 144-146 行，T4 接入）；`ChatService.buildSystemPrompt` 第 97 行、`AiOpsService` Prompt 第 150/244/271 行中的 "ap-guangzhou" 文案改为引用 `QueryLogsTools.DEFAULT_REGION` 常量（或统一常量类 `RegionConst`），不改变提示词语义。
4. **死代码处理**：
   - 删除 `dto/AIOpsRequest.java`（全工程 grep 无引用，仅 `app.js` 有同名 `sendAIOpsRequest` 前端方法，与后端 DTO 无关）。
   - 删除各 `TOOL_*` 常量（`DateTimeTools.TOOL_GET_CURRENT_DATETIME`、`InternalDocsTools.TOOL_QUERY_INTERNAL_DOCS`、`QueryLogsTools.TOOL_QUERY_LOGS/TOOL_GET_AVAILABLE_LOG_TOPICS`、`QueryMetricsTools.TOOL_QUERY_PROMETHEUS_ALERTS`）——注释自称"用于动态构建提示词"，但全工程无消费点（已 grep 核实），属死常量，删除。
   - 移除 `tool/DropCollection.java`（`src/main/java/org/example/tool/` 下的独立 `main` 工具，硬编码 `localhost:19530` 与 `biz`，非应用代码）：移到 `src/main/resources/scripts/` 之外或直接删除（重建 Collection 的能力由 P2 索引迁移流程 + Milvus Attu 承担）。本阶段建议**移出 `src/main/java`** 到仓库根 `scripts/`（不参与编译），若确认无使用则删除。
   - `MilvusConstants.MILVUS_DB_NAME`（第 8 行，无引用）与 `MilvusProperties.database`（第 14 行 + `getDatabase()` 第 49 行，无引用）：建议删除或标注"P2 collection 配置化时启用"——因 `MilvusClientFactory.connectToMilvus` 未使用 database 字段（当前连默认库）。删除 `MILVUS_DB_NAME` 常量与 `database` 属性；若 P2 需要多库，届时在 `MilvusProperties` 恢复。
5. **`WebConfig` 自定义 `ObjectMapper` Bean 处置**（第 34-37 行）：
   - 现状：`@Bean ObjectMapper objectMapper()` 返回裸 `new ObjectMapper()`，会被 Spring 用作全局消息转换器的默认 ObjectMapper，但未注册 `JavaTimeModule`（工程使用了 `LocalDateTime`/`Instant`，实际由 `MappingJackson2HttpMessageConverter` 第 29-31 行也是裸实例，二者都缺 JSR-310 配置，存在潜在序列化问题）。
   - 建议：**删除该 Bean**，改用 Spring Boot 自动配置的 `ObjectMapper`（自带 `JavaTimeModule`、`WRITE_DATES_AS_TIMESTAMPS` 等标准配置），`configureMessageConverters`（第 22-32 行）不再手造 `MappingJackson2HttpMessageConverter`，改为使用容器注入的 `ObjectMapper` 构造转换器（或删掉第 29-31 行的裸 converter，保留字符串 UTF-8 converter 即可）。
   - 理由：手写 `new ObjectMapper()` 覆盖了 Boot 自动配置，丢失时间/命名等默认策略，是后续 JSON 序列化 bug 的隐患；统一用 Boot 托管 ObjectMapper 与 P0"JSON 一律 Jackson"及 P2"复用 WebConfig 已注册 Bean"的假设对齐。
   - 影响面：工具类（`QueryLogsTools`/`QueryMetricsTools`/`InternalDocsTools`）内部各自 `new ObjectMapper()`（如 `QueryLogsTools` 第 36 行）不改（工具类自持 mapper 无碍），仅移除 WebConfig 的全局 Bean 干扰。

**验收标准**
- grep 确认：`buildMethodToolsArray` 仅 1 处定义；`String.format` 拼 JSON 模式在业务代码中为 0；`65535`、`300000L`、`600000L`、`chunkSize = 50`、裸 `"ap-guangzhou"` 字符串全部收敛为常量引用。
- 全工程 `ApiResponse` 仅 `common.ApiResponse` 一处；`AIOpsRequest.java`、`DropCollection.java`（移出 src/main）、各 `TOOL_*` 常量已删除，`mvn compile` 通过。
- `WebConfig` 不再自定义 `ObjectMapper` Bean 与裸 `MappingJackson2HttpMessageConverter`；`LocalDateTime` 序列化回归正常（如 `IndexingResult.startTime`）。
- 回归：`/api/chat`、`/api/ai_ops`、工具调用（`queryPrometheusAlerts`/`queryLogs`/`queryInternalDocs`）行为与改造前一致（见 6.2）。

---

### T7｜P1-中｜CI：本地脚本优先 + 可选 GitHub Actions 模板 + 密钥门禁

**目标**：在不引入外部 CI 平台的前提下，先落地本地可执行的门禁脚本；提供可选 GitHub Actions 模板以备接入远程。

**涉及文件**
- 新增（允许）：
  - `scripts/ci.sh`（本地门禁脚本，PowerShell 友好可另附 `scripts/ci.ps1`，二选一按部署环境）
  - `.github/workflows/ci.yml`（可选模板，未初始化 Git 时不生效，仅作为模板交付）
- 修改：`Makefile`（新增 `make test`、`make check`、`make ci` 目标，或指向 `scripts/ci.sh`）

**具体改动**
1. **本地门禁脚本 `scripts/ci.sh`**（顺序执行，任一失败即 `set -e` 退出非 0）：
   ```bash
   set -euo pipefail
   # 1) build + test + 覆盖率报告
   mvn -B clean verify
   # 2) 静态检查：P0 要求的密钥明文 grep 门禁（禁 sk- 入库）
   if grep -RIn --exclude-dir=target --exclude-dir=.git \
        -E 'sk-[A-Za-z0-9]{8,}' src/ docs/ *.yml *.xml 2>/dev/null; then
     echo "ERROR: 检测到疑似 DashScope API Key 明文，禁止入库"; exit 1
   fi
   # 3) 其它轻量门禁（可选）：禁 System.out.print、禁 String.format 拼 JSON（配合 T6）
   ```
   密钥门禁注意：`application.yml` 经 P0 T1 改造后已无 `sk-` 明文；门禁扫描 `src/`、`docs/`、根配置，忽略 `target/`；`docs/plans/*.md` 中若残留历史示例密钥需一并清理（P0 已要求全量检索）。
2. **`Makefile` 增加目标**：`test`（`mvn test`）、`ci`（`sh scripts/ci.sh`）、`check`（`sh scripts/ci.sh` 别名）。不改动现有 `init/up/start/upload` 等目标。
3. **可选 GitHub Actions 模板 `.github/workflows/ci.yml`**（仅作模板，仓库未 init Git 时不影响构建）：
   ```yaml
   name: ci
   on: [push, pull_request]
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { distribution: temurin, java-version: '17' }
         - run: ./scripts/ci.sh   # 复用本地脚本，避免两套逻辑漂移
   ```
   要点：CI 复用 `scripts/ci.sh`（单一门禁来源），不在 yml 里重复写测试命令；测试无需 DashScope 密钥（全 mock，见 D1），故 CI 无需注入 `DASHSCOPE_API_KEY`。

**验收标准**
- `sh scripts/ci.sh`（或 `make ci`）在干净环境（无环境变量）跑通：build 成功、测试全绿、覆盖率达标、门禁通过。
- 人为在 `src/` 下临时写入含 `sk-xxx` 的文件后，脚本以非 0 退出并报错；移除后恢复通过。
- `Makefile` 新增 `test`/`ci` 目标可执行；`.github/workflows/ci.yml` 存在且与脚本逻辑一致（若仓库日后 `git init` + 推 GitHub 即可生效）。

---

## 4. 关键技术决策

### D1 测试策略：纯 Mockito 单测 vs 集成测试（Testcontainers/真实 Milvus）
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 纯单元测试 + Mockito 打桩（**选定**） | 无外置密钥/网络依赖，CI 离线可跑；覆盖核心逻辑与失败分支；与 P2 T1"mock Repository 打桩 MilvusServiceClient"方向一致 | 无法覆盖真实 Milvus SDK 行为与 schema 正确性 |
| B. Testcontainers 拉起真实 Milvus | 覆盖 gRPC 交互与 schema | 需 Docker、下载镜像，CI 成本高；P1 无容器化交付物（P2 T8 才做） |
| C. 连接真实 DashScope/Milvus 的集成测试 | 覆盖端到端 | 烧 token 费、需密钥、不稳定，违背 P0 密钥外置精神 |

**选 A**：本阶段目标是"工程化底座"而非验证外部依赖；Milvus schema/索引正确性靠 P2 容器化后的集成测试补。Service 层 mock `MilvusServiceClient`/`VectorEmbeddingService`，纯函数直接断言。P2 T1 的 `VectorRepository` 抽取后，单测从"mock MilvusServiceClient"升级为"mock Repository"，测试不重写。

### D2 统一响应 `code` 语义：code=0 成功 vs code=200 成功
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. code=0 成功（**选定**，P1 首次拍板） | 与 P0 5.2 错误体"code=HTTP 码"形成"0=成功/非 0=错误"的稳定判别；前端统一判 `code===0` | 需同步改后端所有 `setCode(200)` 成功点与前端 `app.js` 对 `code===200` 的既有判断 |
| B. 沿用 code=200 成功 | 前端不改 | 与 P0 错误体"code=HTTP 码"语义冲突（成功 200 与错误 400/500 同属 HTTP 码，无法用 code 是否为 0 判别成败） |

**选 A**：P0 5.2 仅定义了错误体 `code=HTTP 码`（400/401/500），未定义成功 code 值；P1 首次拍板成功 `code=0`（与错误体"非 0=错误"形成统一判别），P2/P3 已跟随。迁移需同步改后端所有 `setCode(200)` 成功点为 0、前端 `app.js` 的 `code===200` 判断（风险 #3）。

### D3 异常兜底：回显 `e.getMessage()` vs 固定脱敏文案
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 固定脱敏文案 + 日志留全量（**选定**） | 不泄露内部实现/路径/堆栈；与 P0 T8"兜底常量 + logger.error"原则一致 | 排障需看日志 |
| B. 回显 `e.getMessage()` | 排障直观 | 可能泄露内部路径/异常细节，违反 P0 脱敏约定 |

**选 A**：`GlobalExceptionHandler` 兜底返回 `{"code":500,"message":"internal error","data":null}`，原始异常由 `logger.error` 全量落日志（日志脱敏规则另按 P0 5.3，不含密钥/向量）。

### D4 新依赖最小集与理由
| 依赖 | 决定 | 理由 |
|---|---|---|
| `spring-boot-starter-test`（test） | **引入** | 测试底座唯一标准选择，含 JUnit 5/Mockito/AssertJ，版本由 BOM 管理 |
| `spring-boot-starter-validation` | **引入** | `@Valid`/`@Size`/`@NotBlank` 等 JSR-303 约束的 Spring Boot 3 标准实现（`jakarta.validation`），T4 参数校验必需 |
| `spring-boot-starter-actuator` | **引入** | T5 可观测性的标准入口，暴露 `/actuator/metrics`、`/actuator/prometheus` |
| `micrometer-registry-prometheus` | **引入** | Prometheus 抓取格式，`/actuator/prometheus` 端点所需；Micrometer 核心已随 Spring Boot 内置 |
| `jacoco-maven-plugin`（build 插件） | **引入** | 覆盖率统计与门禁，T1 目标"核心逻辑 70%+"的度量手段 |

不引入：`spring-boot-starter-security`（P0 D2 已定不引入）、Testcontainers（D1）、`lombok` 新版本（仅交 BOM）、任何 JSON 库（Jackson 已在栈内）。

### D5 指标埋点层级：Service 层计时 vs 切面/AOP vs 手动计数器
| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 手动埋点（Timer.Sample + Counter，**选定**） | 显式、可控、零额外依赖；落点精确（LLM/embedding/Milvus 调用处）；token 计数可读 usage | 需在每处调用点写 3-5 行 |
| B. AOP/切面统一拦截 | 一处定义 | 需按方法名/注解匹配，对 SDK 方法（`textEmbedding.call`/`agent.call`）无法切入；token 计数做不到 |
| C. 仅靠 Actuator 默认指标（JVM/HTTP） | 零代码 | 无 LLM/embedding/Milvus 业务指标，无法满足 P2 压测口径 |

**选 A**：远程付费调用（LLM/embedding）与外部依赖（Milvus）是核心成本/故障点，必须精确埋点；AOP 对第三方 SDK 调用无能为力。指标命名 `argus.` 前缀与 P2 5.2 完全一致。

---

## 5. 与其它阶段的依赖关系

### 5.1 沿用 P0（`docs/plans/P0-security.md` 第 5 节）约定情况

| P0 约定 | P1 沿用方式 |
|---|---|
| 敏感配置无默认值外置（`DASHSCOPE_API_KEY`、`MCP_TENCENT_CLS_SSE_ENDPOINT`、`APP_API_KEY`、`security.allowed-origins`） | 原样沿用，不动；测试/CI 全程无需这些密钥（全 mock），启动校验逻辑不动 |
| `X-API-Key` 鉴权，`/api/**`、`/milvus/**` 强制，静态资源免鉴权 | 原样沿用；新增 `/actuator/**` 建议纳入鉴权（风险 #5），不改 Header 约定 |
| 统一错误体：成功 `{"code":0,...}`；错误 `{"code":<HTTP码>,"message":<脱敏文本>,"data":null}` | T3 的 `common.ApiResponse`/`GlobalExceptionHandler` 严格输出同形状，HTTP 语义 400/401/500 不变 |
| JSON 一律 Jackson，禁止 `String.format` 拼 JSON | T6 三处 `buildErrorResponse`/工具错误分支统一 Jackson；T7 CI 加"禁 String.format 拼 JSON"轻量门禁 |
| 日志不得打印密钥/完整向量/完整凭证 URL | T5 埋点与 T3 异常日志遵守；traceId 日志不含业务敏感内容 |
| Milvus 表达式 `escapeExprString()` + 文件名白名单 `SAFE_FILENAME_REGEX=[A-Za-z0-9\u4e00-\u9fa5._-]{1,128}` | P0 建议的公共类 `org.example.util.FilenameSanitizer` 在 P1 落地（T1 扩展名校验抽取时一并产出），供上传/删除/校验共用 |
| 静态资源 `/`、`/index.html`、`/app.js`、`/styles.css`、`/vendor/**`、`/error` 免鉴权 | 不动 |

### 5.2 与 P2（`docs/plans/P2-performance.md` 第 5 节）横向约定的对齐

| P2 约定 | P1 对齐方式 |
|---|---|
| 指标前缀 `argus.`（`argus.llm.calls/latency/tokens`、`argus.embedding.calls/latency`、`argus.milvus.ops/latency`、`argus.sse.sessions.active`） | T5 指标命名**完全沿用该列表**，不另起；P2 新增的 `argus.index.*`/`argus.redis.*`/`argus.cache.embedding.*` 挂同一前缀 |
| MDC traceId | T5 `TraceIdFilter` 产出 traceId；P2 任务线程 `TaskDecorator` 透传复用，字段名 `traceId` 一致 |
| 公共类落点 `org.example.common`（ApiResponse/BizException/GlobalExceptionHandler）与 `org.example.util.FilenameSanitizer` | T3/T1 严格落此两包；P2 的 `SessionStore` 系列落 `org.example.common.session` 不冲突 |
| `VectorRepository` 落点 `org.example.repository` | P1 不占该包（P2 T1 专属）；T1 的 Milvus 单测打桩 `MilvusServiceClient`，P2 抽 Repository 后改打桩 Repository |
| Redis key 前缀 `argus:` | P1 不引入 Redis，不占用 key；该前缀约定由 P2 落地 |
| 索引任务 API 走 `/api/index/tasks` | P1 不新增 `/api/**` 业务端点（仅 `/actuator/**` 需鉴权说明）；`/api/index/tasks` 由 P2 落地 |
| `BizException` + `GlobalExceptionHandler` 承接 400/429/503 | T3/T4 落地该体系，P2 的文件超限(400)、队列满(503)、限流(429) 直接复用 |
| JUnit 5 + Mockito 测试底座 | T1 落地，P2 各验收单测基于此底座 |

### 5.3 P1 产出的对外约定（供 P2/P3 原样沿用）

1. **测试约定**：`src/test/java` 包结构镜像 `src/main/java`；测试类命名 `{被测类}Test`；方法命名 `{方法}_{场景}_{期望}`；断言 AssertJ、mock Mockito；禁止真实网络/容器；覆盖率门禁 `org.example.service`/`org.example.agent.tool` ≥ 70%（JaCoCo）。
2. **统一异常约定**：业务异常一律抛 `org.example.common.BizException`（携带 `ErrorCode` + 脱敏 message）；所有 HTTP 错误经 `org.example.common.GlobalExceptionHandler` 输出 `{"code":<HTTP码>,"message":<脱敏文本>,"data":null}`；兜底 `Exception` 固定返回 `{"code":500,"message":"internal error","data":null}` 不泄露堆栈。SSE 流内错误例外（用 `SseMessage.error`）。
3. **统一响应约定**：成功 `{"code":0,"message":"success","data":<对象>}`（`org.example.common.ApiResponse` 唯一实现，`code=0` 而非 200）。
4. **指标命名约定**：统一前缀 `argus.`，四类基础指标 `argus.llm.*`/`argus.embedding.*`/`argus.milvus.*`/`argus.sse.sessions.active`；P2/P3 新增指标同前缀。
5. **traceId 约定**：请求头 `X-Trace-Id`（缺省生成 UUID），MDC 键 `traceId`，响应头回写；异步链路用 `TaskDecorator` 透传。
6. **依赖治理约定**：版本一律由 Spring Boot BOM 管理，禁止 `dependencyManagement` 手动锁定 BOM 已管理的 artifact；JSON 业务序列化一律 Jackson，Gson 仅限 Milvus metadata 边界；编译器用 `release 17`。
7. **工具类落点约定**：`org.example.util`（`FilenameSanitizer`、`JsonUtil` 等），`org.example.common`（ApiResponse/BizException/ErrorCode/GlobalExceptionHandler/TraceIdFilter）。

---

## 6. 验证方案

### 6.1 测试怎么补

- **纯函数单测**（T1）：`DocumentChunkService.chunkDocument` 分块（空输入/标题切分/超长段落+overlap/句子边界重叠）、`VectorEmbeddingService.getFloats` 与 `calculateCosineSimilarity`（Double→Float 截断、维度不匹配、正交=0）、`FileUploadController` 扩展名校验（白名单命中/拒绝/大小写/无后缀）、`QueryMetricsTools.calculateDuration`（h/m/s 三档 + 非法串）。
- **Service 层 Mockito 单测**（T1）：`VectorIndexService.indexSingleFile`（文件不存在、embedding 异常上抛、loadCollection 65535 容错分支），mock `MilvusServiceClient`/`VectorEmbeddingService`/`DocumentChunkService`。
- **异常体系单测**（T3）：`GlobalExceptionHandler` 对 `BizException`/`MethodArgumentNotValidException`/`Exception` 三类的响应体与 HTTP 码。
- **参数校验单测**（T4）：`VectorSearchService` topK 边界（0/1/20/21）、`QueryLogsTools.resolveRegion`（合法/非法/null）。
- **指标冒烟**（T5）：`/actuator/prometheus` 可见 `argus.*` 指标；traceId 透传。

### 6.2 怎么回归

| 链路 | 方法 |
|---|---|
| 构建 | `mvn clean verify`（build + test + JaCoCo 报告 + 门禁）在无环境变量的干净环境跑通 |
| 对话 | `/api/chat`（带 `X-API-Key`）一次问答；空 Question 返回 400；业务失败返回 500 统一错误体（非 200 包裹） |
| 流式 | `/api/chat_stream`、`/api/ai_ops` SSE 正常收流；流内错误走 `SseMessage.error`；`argus.sse.sessions.active` 建立/归零 |
| 上传 | `/api/upload` 上传 txt/md 正常 + 非法扩展名 400 + 攻击文件名 400（P0 T2 回归）；`getFileExtension` 白名单行为不变 |
| 工具调用 | `queryPrometheusAlerts`/`queryLogs`/`queryInternalDocs` 正常路径返回 JSON 结构不变；错误分支返回合法 JSON（`readValue` 可解析） |
| 依赖 | `mvn dependency:tree` 无 jackson 版本漂移、无 devtools、lombok 来自 BOM |
| 死代码 | grep `AIOpsRequest`/`TOOL_*`/`MILVUS_DB_NAME`/`DropCollection`（src/main）命中 0 |

### 6.3 上线前检查清单（发布门禁）

- [ ] `mvn clean verify` 全绿，JaCoCo 覆盖率 `org.example.service`/`org.example.agent.tool` ≥ 70%
- [ ] `sh scripts/ci.sh` 通过（含密钥明文 grep 门禁：`sk-` 入库 0 命中）
- [ ] 全工程 `class ApiResponse` 仅 `org.example.common` 一处；`code=0` 成功语义生效
- [ ] `/api/chat` 业务失败返回 500 统一错误体，不再 HTTP 200 包裹 error
- [ ] `/actuator/**` 有鉴权或确认仅内网暴露（风险 #5）
- [ ] `X-API-Key` 鉴权 + 401 错误体回归通过（P0 T6）
- [ ] 参数校验：空/超长 Question、非法 topK、非法 region 全部按预期（400 或回退）
- [ ] `/actuator/prometheus` 可见 `argus.llm/embedding/milvus/sse` 指标，命名与 P2 一致
- [ ] 前端 `app.js` 对响应 `code` 的判断点已排查并适配 `code=0`（风险 #3）
- [ ] 本方案 5.3 节约定已同步至 P2/P3 方案文档

---

## 7. 风险与应对

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | Jackson 锁版交回 BOM 后，Spring AI Alibaba / dashscope-sdk 传递依赖要求更高 Jackson 版本，导致冲突或行为变化 | 序列化异常、构建失败 | T2 执行时 `mvn dependency:tree` 验证；若确有传递依赖要求 2.17，保留最小必要项并在 pom 注释说明"为何脱离 BOM"，其余交回 BOM |
| 2 | Spring Boot 升 3.2.x 最新 patch 引入与 Spring AI Alibaba 1.1.0.0-RC2 的兼容问题 | 启动失败 | patch 升级风险低但非零；T2 落地后立即 `java -jar` 冒烟 + 全链路回归；若出现不兼容，回退到当前 3.2.0 并记录原因，不强行升级 |
| 3 | 统一响应 `code` 从 200 改 0，前端 `app.js` 存在 `code===200` 判断未同步 | 前端判定成功/失败逻辑失效 | T3 实施时全文检索 `app.js` 中 `code` 判断点（当前 `ApiResponse` 消费点），同批改前端；P0 T6 已把前端 fetch 收敛到 4 处，排查面可控 |
| 4 | 删除 `getFloats` 等 private 方法的访问限制放宽后，被误当公共 API 使用 | 接口面扩大 | 仅放宽到 package-private（非 public），测试同包访问；类注释标注"包级可见仅为单测" |
| 5 | Actuator 端点默认无鉴权，暴露内部指标/健康信息 | 信息泄露 | `/actuator/**` 纳入 P0 的 `X-API-Key` 过滤器（或仅内网/回环暴露）；`health.show-details: never` |
| 6 | 删除 `ToolCallback` 相关的 `TOOL_*` 常量后，若实际有运行时反射依赖（注释声称"动态构建提示词"） | 工具注册失效 | 已 grep 核实无消费点；删除前再确认 `spring-ai-alibaba` 工具框架是否通过反射按常量名加载（当前框架用 `@Tool` 注解发现，不依赖字符串常量），T6 回归工具调用验证 |
| 7 | 门禁脚本误杀 `docs/plans/*.md` 中 P0 历史示例密钥（`sk-ws-` 片段） | 门禁误报 | P0 已要求全量清理历史交付物中的密钥片段；T7 门禁扫描范围 `src/`、根配置，`docs/` 仅告警不阻断（或一并清理后再纳入阻断） |
| 8 | 移除 `WebConfig` 自定义 ObjectMapper 后，依赖其默认行为（如 pretty print、字段名）的代码受影响 | 序列化行为变化 | 统一改用 Boot 自动配置 ObjectMapper（更标准）；回归工具类 JSON 输出与上传/对话响应字段名一致（现有 POJO 用 `@JsonProperty` 显式标注，不受影响） |
| 9 | 测试底座"全 mock、不连真实 Milvus/DashScope"掩盖真实集成问题 | 上线才发现 SDK/schema 问题 | 明示测试边界（D1）；真实 Milvus/DashScope 的集成验证由 P2 容器化（T8）+ 手动冒烟补；P1 仅保证纯逻辑与失败分支正确 |
| 10 | 门禁脚本依赖 `mvn`/`grep` 环境，跨平台（Windows PowerShell vs Linux bash）不一致 | CI 不可复现 | 提供 `scripts/ci.sh`（Linux/macOS）为主、`scripts/ci.ps1`（Windows）等价实现；CI 模板用 ubuntu runner 固定环境，本地 Windows 用 ps1 |
