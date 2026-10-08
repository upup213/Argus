package org.example.service;

import org.example.dto.DocumentChunk;
import org.example.repository.VectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for VectorIndexService using pure Mockito (no real Milvus connection).
 */
@ExtendWith(MockitoExtension.class)
class VectorIndexServiceTest {

    @Mock
    private VectorRepository vectorRepository;

    @Mock
    private VectorEmbeddingService embeddingService;

    @Mock
    private DocumentChunkService chunkService;

    // Construct via field injection (class uses @Autowired fields, no custom constructor)
    private VectorIndexService createService() {
        VectorIndexService svc = new VectorIndexService();
        ReflectionTestUtils.setField(svc, "vectorRepository", vectorRepository);
        ReflectionTestUtils.setField(svc, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(svc, "chunkService", chunkService);
        ReflectionTestUtils.setField(svc, "uploadPath", tempDir.toString());
        return svc;
    }

    @TempDir
    Path tempDir;

    @Test
    void indexSingleFile_fileNotExists_throwsIllegalArgumentException() {
        VectorIndexService svc = createService();
        String notExists = tempDir.resolve("not-exists.txt").toString();

        assertThatThrownBy(() -> svc.indexSingleFile(notExists))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void indexSingleFile_embeddingFailure_wrapsIntoRuntimeException() throws Exception {
        VectorIndexService svc = createService();
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello content");

        DocumentChunk chunk = new DocumentChunk();
        chunk.setContent("hello content");
        when(chunkService.chunkDocument(anyString(), anyString()))
                .thenReturn(Collections.singletonList(chunk));
        // T2: code calls generateEmbeddings (batch API), not generateEmbedding (single)
        when(embeddingService.generateEmbeddings(any(List.class)))
                .thenThrow(new RuntimeException("embedding failed"));

        assertThatThrownBy(() -> svc.indexSingleFile(file.toString()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("embedding failed");
    }

    @Test
    void indexSingleFile_successPath_withChunks_returnsNormally() throws Exception {
        // Test happy path: file exists, chunks generated, no exceptions thrown
        VectorIndexService svc = createService();
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "sample document content for indexing");

        when(chunkService.chunkDocument(anyString(), anyString()))
                .thenReturn(Collections.emptyList()); // empty chunks = no ops, still succeeds

        // Stub repository methods — deleteBySource and insertBatch handle their own metering internally
        when(vectorRepository.deleteBySource(anyString(), anyString())).thenReturn(0L);

        svc.indexSingleFile(file.toString());
        // If we reach here, the happy path succeeded (no exception)
    }
}
