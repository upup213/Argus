package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.common.ApiResponse;
import org.example.common.BizException;
import org.example.common.ErrorCode;
import org.example.common.session.SessionStore;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    /** SSE 流式聊天超时（5分钟） */
    private static final long CHAT_STREAM_TIMEOUT_MS = 300000L;
    /** SSE 智能运维超时（10分钟） */
    private static final long AI_OPS_TIMEOUT_MS = 600000L;
    /** 告警报告分块大小 */
    private static final int REPORT_CHUNK_SIZE = 50;
    /** 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对） */
    private static final int MAX_WINDOW_SIZE = 6;

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired
    private SessionStore sessionStore;

    /** 使用单例 ChatModel Bean，避免每次请求新建实例 */
    @Autowired
    private DashScopeChatModel standardChatModel;

    /** AI Ops 专用低温度 ChatModel Bean */
    @Autowired
    private DashScopeChatModel aiOpsChatModel;

    /** 有界线程池用于 SSE 流式请求，替代无界 cachedThreadPool */
    @Autowired
    @Qualifier("sseExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    // 本地跟踪活跃会话（仅用于 LRU 监控和 Prometheus Gauge），不参与实际存储
    private final Map<String, Long> activeSessions = new HashMap<>();

    /** 活跃 SSE 流式会话计数（用于 Prometheus Gauge） */
    private final AtomicInteger sseActiveSessions = new AtomicInteger();

    /** 会话最大数量限制，超过时触发 LRU 淘汰 */
    @Value("${session.max-size:10000}")
    private int sessionMaxSize;

    /** 会话 TTL，用于本地跟踪条目的过期清理 */
    @Value("${session.ttl:24h}")
    private Duration sessionTtl;

    @PostConstruct
    public void initGauges() {
        if (meterRegistry != null) {
            Gauge.builder("argus.sse.sessions.active", sseActiveSessions::get).register(meterRegistry);
        }
    }

    /**
     * 普通对话接口（支持工具调用）
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody @jakarta.validation.Valid ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            String sessionId = getOrCreateSession(request.getId());

            // 获取历史消息
            List<SessionStore.MessageEntry> history = sessionStore.getMessages(sessionId);
            logger.info("会话历史消息对数: {}", history.size() / 2);

            // 使用注入的单例 ChatModel（标准温度参数）
            logger.info("开始 ReactAgent 对话（支持自动工具调用）");

            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(history);

            // 创建 ReactAgent — 直接使用注入的 standardChatModel Bean
            ReactAgent agent = chatService.createReactAgent(standardChatModel, systemPrompt);

            // 执行对话
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());

            // 更新会话历史
            sessionStore.addMessageToSession(sessionId, "user", request.getQuestion());
            sessionStore.addMessageToSession(sessionId, "assistant", fullAnswer);

            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                    sessionId, sessionStore.getMessages(sessionId).size() / 2);

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer, sessionId)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "对话失败");
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "会话ID不能为空");
            }

            if (!sessionStore.exists(request.getId())) {
                throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
            }

            sessionStore.clearSession(request.getId());
            activeSessions.remove(request.getId());
            return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "清空会话历史失败");
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用）
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody @jakarta.validation.Valid ChatRequest request) {
        SseEmitter emitter = new SseEmitter(CHAT_STREAM_TIMEOUT_MS);

        // 参数校验 — 不增加活跃计数
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 先提交任务到线程池，再递增活跃计数（确保 increment/decrement 配对）
        CompletableFuture.runAsync(() -> {
            sseActiveSessions.incrementAndGet();
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                String sessionId = getOrCreateSession(request.getId());

                // 发送 sessionId 作为首个 SSE frame
                emitter.send(SseEmitter.event()
                        .name("session")
                        .data(SseMessage.session(sessionId), MediaType.APPLICATION_JSON));

                // 获取历史消息
                List<SessionStore.MessageEntry> history = sessionStore.getMessages(sessionId);
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 使用注入的单例 ChatModel（标准温度参数）
                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history);

                // 创建 ReactAgent — 直接使用注入的 standardChatModel Bean
                ReactAgent agent = chatService.createReactAgent(standardChatModel, systemPrompt);

                // 用于累积完整答案（StringBuffer 保证线程安全）
                StringBuffer fullAnswerBuilder = new StringBuffer();

                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                stream.subscribe(
                    output -> {
                        try {
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();

                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                    sessionId, fullAnswer.length());

                            // 更新会话历史
                            sessionStore.addMessageToSession(sessionId, "user", request.getQuestion());
                            sessionStore.addMessageToSession(sessionId, "assistant", fullAnswer);

                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                                    sessionId, sessionStore.getMessages(sessionId).size() / 2);

                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        } finally {
                            sseActiveSessions.decrementAndGet();
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
                sseActiveSessions.decrementAndGet();
            }
        }, sseExecutor);

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(AI_OPS_TIMEOUT_MS);

        CompletableFuture.runAsync(() -> {
            sseActiveSessions.incrementAndGet();
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                // 使用注入的 AI Ops ChatModel Bean（低温度、大上下文）
                ToolCallback[] toolCallbacks = tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(aiOpsChatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    sseActiveSessions.decrementAndGet();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    for (int i = 0; i < finalReportText.length(); i += REPORT_CHUNK_SIZE) {
                        int end = Math.min(i + REPORT_CHUNK_SIZE, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);

                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));

                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");
                sseActiveSessions.decrementAndGet();

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
                sseActiveSessions.decrementAndGet();
            }
        }, sseExecutor);

        return emitter;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            if (sessionId == null || sessionId.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "会话ID不能为空");
            }

            if (!sessionStore.exists(sessionId)) {
                throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
            }

            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(sessionId);

            // 从元数据获取创建时间
            sessionStore.getSession(sessionId).ifPresent(data ->
                    response.setCreateTime(data.getCreateTime()));

            // 从消息列表获取消息对数
            List<SessionStore.MessageEntry> messages = sessionStore.getMessages(sessionId);
            response.setMessagePairCount(messages.size() / 2);

            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "获取会话信息失败");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取或创建会话 ID
     * 包含 LRU 淘汰保护
     */
    private String getOrCreateSession(String providedId) {
        String sessionId = (providedId != null && !providedId.isEmpty()) ? providedId : UUID.randomUUID().toString();

        // LRU 淘汰：当会话数已达上限时，移除最久未访问的会话
        if (activeSessions.size() >= sessionMaxSize) {
            String lruKey = activeSessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Long::compareTo))
                    .map(Map.Entry::getKey).orElse(null);
            if (lruKey != null) {
                logger.info("会话数已达上限({})，淘汰最久未访问会话: {}", sessionMaxSize, lruKey);
                sessionStore.clearSession(lruKey);
                activeSessions.remove(lruKey);
            }
        }

        // 如果会话不存在，初始化它
        if (!sessionStore.exists(sessionId)) {
            SessionStore.SessionData meta = new SessionStore.SessionData();
            meta.setSessionId(sessionId);
            meta.setCreateTime(System.currentTimeMillis());
            sessionStore.saveSession(sessionId, meta);
            logger.info("创建新会话 - SessionId: {}", sessionId);
        }

        // 追踪活跃会话
        activeSessions.put(sessionId, System.currentTimeMillis());

        return sessionId;
    }

    /**
     * 定时清理过期的会话（每小时执行一次）
     * Redis 场景下主要清理本地跟踪的 stale 条目
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        long cutoff = System.currentTimeMillis() - sessionTtl.toMillis();
        activeSessions.entrySet().removeIf(e -> e.getValue() < cutoff);
        if (!activeSessions.isEmpty()) {
            logger.info("清理过期会话 tracking 条目，剩余: {}", activeSessions.size());
        }
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        @jakarta.validation.constraints.Size(max = 64, message = "会话ID过长")
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        @jakarta.validation.constraints.NotBlank(message = "问题不能为空")
        @jakarta.validation.constraints.Size(max = 4096, message = "问题长度不能超过4096字符")
        private String Question;
    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String sessionId;
        private String errorMessage;

        public static ChatResponse success(String answer, String sessionId) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            response.setSessionId(sessionId);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }

        public static SseMessage session(String sessionId) {
            SseMessage message = new SseMessage();
            message.setType("session");
            message.setData(sessionId);
            return message;
        }
    }

}
