package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorEmbeddingServiceTest {

    @Test
    void getFloats_nullResult_throwsRuntimeException() {
        assertThatThrownBy(() -> VectorEmbeddingService.getFloats(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("空结果");
    }

    @Test
    void getFloats_nullOutput_throwsRuntimeException() {
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(null);

        assertThatThrownBy(() -> VectorEmbeddingService.getFloats(result))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("空结果");
    }

    @Test
    void getFloats_nullEmbeddings_throwsRuntimeException() {
        TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
        when(output.getEmbeddings()).thenReturn(null);
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);

        assertThatThrownBy(() -> VectorEmbeddingService.getFloats(result))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("空结果");
    }

    @Test
    void getFloats_emptyEmbeddings_throwsRuntimeException() {
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(Collections.emptyList());
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);

        assertThatThrownBy(() -> VectorEmbeddingService.getFloats(result))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("空向量列表");
    }

    @Test
    void getFloats_validResult_convertsDoubleToFloatWithPrecisionTruncation() {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem();
        item.setEmbedding(Arrays.asList(0.1234567890123456789, -2.5, 3.0));
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(Collections.singletonList(item));
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);

        List<Float> floats = VectorEmbeddingService.getFloats(result);

        assertThat(floats).hasSize(3);
        assertThat(floats.get(0)).isEqualTo((float) 0.1234567890123456789);
        assertThat(floats.get(1)).isEqualTo(-2.5f);
        assertThat(floats.get(2)).isEqualTo(3.0f);
    }

    @Test
    void calculateCosineSimilarity_dimensionMismatch_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new VectorEmbeddingService().calculateCosineSimilarity(
                Arrays.asList(1.0f, 2.0f),
                Collections.singletonList(1.0f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度不匹配");
    }

    @Test
    void calculateCosineSimilarity_orthogonalVectors_returnsZero() {
        float similarity = new VectorEmbeddingService().calculateCosineSimilarity(
                Arrays.asList(1.0f, 0.0f),
                Arrays.asList(0.0f, 1.0f));

        assertThat(similarity).isEqualTo(0.0f);
    }

    @Test
    void calculateCosineSimilarity_identicalVectors_returnsOne() {
        float similarity = new VectorEmbeddingService().calculateCosineSimilarity(
                Arrays.asList(3.0f, 4.0f),
                Arrays.asList(3.0f, 4.0f));

        assertThat(similarity).isEqualTo(1.0f);
    }
}
