package org.example.repository;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.config.MilvusProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-Mockito tests for VectorRepository (no real Milvus instance required).
 */
@ExtendWith(MockitoExtension.class)
class VectorRepositoryTest {

    @Mock
    private MilvusServiceClient client;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private VectorRepository repository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        MilvusProperties props = new MilvusProperties();
        repository = new VectorRepository();
        ReflectionTestUtils.setField(repository, "client", client);
        ReflectionTestUtils.setField(repository, "milvusProperties", props);
        ReflectionTestUtils.setField(repository, "meterRegistry", meterRegistry);
    }

    // ---------------------------------------------------------------
    // loadCollectionOnce
    // ---------------------------------------------------------------

    @Test
    void loadCollectionOnce_statusZero_succeeds() {
        R<RpcStatus> resp = mock(R.class);
        when(resp.getStatus()).thenReturn(0);
        when(client.loadCollection(any(LoadCollectionParam.class))).thenReturn(resp);

        repository.loadCollectionOnce("test_collection");

        verify(client).loadCollection(any(LoadCollectionParam.class));
    }

    @Test
    void loadCollectionOnce_errorStatus_logsWarning_doesNotThrow() {
        R<RpcStatus> errorResp = mock(R.class);
        when(errorResp.getStatus()).thenReturn(1);
        when(errorResp.getMessage()).thenReturn("error");
        when(client.loadCollection(any(LoadCollectionParam.class))).thenReturn(errorResp);

        assertThatCode(() -> repository.loadCollectionOnce("bad"))
                .doesNotThrowAnyException();

        verify(client).loadCollection(any(LoadCollectionParam.class));
    }

    // ---------------------------------------------------------------
    // deleteBySource
    // ---------------------------------------------------------------

    @Test
    void deleteBySource_normalizesPathAndEscapesBackslash() {
        // Path with backslash — normalized to forward slash, then escaped
        String filePath = "C:\\upload\\test-file.txt";

        MutationResult mresult = mock(MutationResult.class);
        when(mresult.getDeleteCnt()).thenReturn(3L);

        // loadCollection returns R<RpcStatus>, delete returns R<MutationResult>
        @SuppressWarnings("unchecked")
        R<MutationResult> delResp = mock(R.class);
        when(delResp.getStatus()).thenReturn(0);
        when(delResp.getData()).thenReturn(mresult);

        @SuppressWarnings("unchecked")
        R<RpcStatus> loadResp = mock(R.class);
        when(loadResp.getStatus()).thenReturn(0);

        when(client.loadCollection(any(LoadCollectionParam.class))).thenReturn(loadResp);
        when(client.delete(any(DeleteParam.class))).thenReturn(delResp);

        long deleted = repository.deleteBySource(filePath, "biz");

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(client).delete(captor.capture());

        DeleteParam param = captor.getValue();
        // Path normalized to forward slashes, backslash not present anymore so no escape needed
        assertThat(param.getExpr())
                .contains("metadata[\"_source\"]")
                .contains("C:/upload/test-file.txt")
                .doesNotContain("\\\\");
        assertThat(deleted).isEqualTo(3L);
    }

    @Test
    void deleteBySource_invalidFileName_returnsZero() {
        // Filename contains double-quote → rejected by whitelist before any Milvus call
        String badPath = "/data/x\"y.txt";
        long deleted = repository.deleteBySource(badPath, "biz");

        assertThat(deleted).isZero();
        verifyNoInteractions(client);
    }

    // ---------------------------------------------------------------
    // search
    // ---------------------------------------------------------------

    @Test
    void search_buildsCorrectParamAndReturnsResponse() {
        R<SearchResults> resp = mock(R.class);
        when(client.search(any(SearchParam.class))).thenReturn(resp);

        List<Float> vec = List.of(1.0f, 2.0f);
        R<SearchResults> result = repository.search(
                "mycoll", vec, 5,
                List.of("id", "content"),
                "{\"nprobe\":10}"
        );

        ArgumentCaptor<SearchParam> captor = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(captor.capture());

        SearchParam p = captor.getValue();
        assertThat(p.getCollectionName()).isEqualTo("mycoll");
        assertThat(p.getTopK()).isEqualTo(5);
        assertThat(p.getOutFields()).containsExactly("id", "content");
        assertThat(p.getParams()).contains("nprobe");
        assertThat(result).isSameAs(resp);
    }

    // ---------------------------------------------------------------
    // insertBatch
    // ---------------------------------------------------------------

    @Test
    void insertBatch_failureThrowsRuntimeException() {
        R<MutationResult> resp = mock(R.class);
        when(resp.getStatus()).thenReturn(1);
        when(resp.getMessage()).thenReturn("insert failed");
        when(client.insert(any(InsertParam.class))).thenReturn(resp);

        List<Float> vec = List.of(0.1f, 0.2f);
        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("vector", Collections.singletonList(vec))
        );

        assertThatThrownBy(() -> repository.insertBatch("mycoll", fields))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("插入向量失败");
    }

    // ---------------------------------------------------------------
    // Static utilities
    // ---------------------------------------------------------------

    @Test
    void escapeExprString_escapesBackslashAndQuote() {
        String result = VectorRepository.escapeExprString("a\\b\"c");
        assertThat(result).isEqualTo("a\\\\b\\\"c");
    }

    @Test
    void generateId_consistentForSameInput() {
        String id1 = VectorRepository.generateId("src", 0);
        String id2 = VectorRepository.generateId("src", 0);
        String id3 = VectorRepository.generateId("src", 1);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
    }
}
