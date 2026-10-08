package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkServiceTest {

    private DocumentChunkService service;

    @BeforeEach
    void setUp() {
        service = new DocumentChunkService();
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(800);
        config.setOverlap(100);
        ReflectionTestUtils.setField(service, "chunkConfig", config);
    }

    @Test
    void chunkDocument_nullContent_returnsEmptyList() {
        assertThat(service.chunkDocument(null, "test.md")).isEmpty();
    }

    @Test
    void chunkDocument_blankContent_returnsEmptyList() {
        assertThat(service.chunkDocument("   \n  \t ", "test.md")).isEmpty();
    }

    @Test
    void chunkDocument_withHeadings_splitsByTitle() {
        String content = "# 标题一\n正文内容一\n\n## 标题二\n正文内容二";
        List<DocumentChunk> chunks = service.chunkDocument(content, "test.md");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getTitle()).isEqualTo("标题一");
        assertThat(chunks.get(1).getTitle()).isEqualTo("标题二");
    }

    @Test
    void chunkDocument_contentExceedsMaxSize_splitsByParagraphsWithOverlap() {
        String para1 = "A".repeat(400);
        String para2 = "B".repeat(400);
        String para3 = "C".repeat(400);
        String content = para1 + "\n\n" + para2 + "\n\n" + para3;

        List<DocumentChunk> chunks = service.chunkDocument(content, "test.md");

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getContent()).isEqualTo(para1);
        assertThat(chunks.get(1).getContent()).isEqualTo("A".repeat(100) + para2);
        assertThat(chunks.get(2).getContent()).isEqualTo("B".repeat(100) + para3);
    }

    @Test
    void getOverlapText_sentenceBoundary_truncatesAtSentenceEnd() {
        String text = "A".repeat(200) + "这是句子结尾。这里是后半段";
        String result = ReflectionTestUtils.invokeMethod(service, "getOverlapText", text);

        assertThat(result).isEqualTo("这里是后半段");
    }
}
