package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Singleton beans for DashScope ChatModel and Embedding.
 * <p>
 * ChatController / EmbeddingCache now use these pre-built beans instead of
 * creating fresh instances per request.
 */
@Configuration
public class ChatModelConfig {

    @Bean
    public DashScopeApi dashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .build();
    }

    @Bean("standardChatModel")
    public DashScopeChatModel standardChatModel(DashScopeApi api) {
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.7)
                        .withMaxToken(2000)
                        .withTopP(0.9)
                        .build())
                .build();
    }

    @Bean("aiOpsChatModel")
    public DashScopeChatModel aiOpsChatModel(DashScopeApi api) {
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();
    }

    /** Shared TextEmbedding instance for EmbeddingCache. */
    @Bean
    public TextEmbedding textEmbedding() {
        return new TextEmbedding();
    }
}
