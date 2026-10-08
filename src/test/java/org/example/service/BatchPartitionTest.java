package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the batch partition/loop logic used in VectorIndexService.indexSingleFile().
 * Verifies correctness of subList boundaries without requiring Milvus or embedding API mocks.
 */
class BatchPartitionTest {

    /**
     * Simulates the batch iteration loop from indexSingleFile step 4 (embedding batching).
     */
    private <T> List<List<T>> simulateEmbeddingBatch(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            batches.add(items.subList(i, end));
        }
        return batches;
    }

    /**
     * Simulates the batch iteration loop from indexSingleFile step 6 (insert batching).
     */
    private <T> List<List<T>> simulateInsertBatch(List<T> items, int batchSize) {
        return simulateEmbeddingBatch(items, batchSize);
    }

    // ---- Embedding batch boundary tests ----

    @Test
    void exactMultiple_batchSize10_100Items_produces10BatchesOf10() {
        List<String> items = buildItems(100);
        List<List<String>> batches = simulateEmbeddingBatch(items, 10);
        assertThat(batches).hasSize(10);
        for (List<String> b : batches) {
            assertThat(b).hasSize(10);
        }
        // Verify no item is lost
        List<String> flattened = new ArrayList<>();
        for (List<String> b : batches) {
            flattened.addAll(b);
        }
        assertThat(flattened).isEqualTo(items);
    }

    @Test
    void remainder_batchSize10_23Items_produces3BatchesLastIs3() {
        List<String> items = buildItems(23);
        List<List<String>> batches = simulateEmbeddingBatch(items, 10);
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(10);
        assertThat(batches.get(1)).hasSize(10);
        assertThat(batches.get(2)).hasSize(3);
    }

    @Test
    void singleItem_batchSize10_singleBatch() {
        List<String> items = List.of("only");
        List<List<String>> batches = simulateEmbeddingBatch(items, 10);
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 13})
    void batchSizeEqualsItemCount_singleBatch(int count) {
        List<String> items = buildItems(count);
        List<List<String>> batches = simulateEmbeddingBatch(items, count);
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(count);
    }

    @Test
    void batchSizeOne_eachItemInOwnBatch() {
        List<String> items = buildItems(5);
        List<List<String>> batches = simulateEmbeddingBatch(items, 1);
        assertThat(batches).hasSize(5);
        for (List<String> b : batches) {
            assertThat(b).hasSize(1);
        }
    }

    @Test
    void emptyList_noBatches() {
        List<String> items = List.of();
        List<List<String>> batches = simulateEmbeddingBatch(items, 10);
        assertThat(batches).isEmpty();
    }

    // ---- Insert batch boundary tests ----

    @Test
    void insertBatch_largeDocument_256BatchSize_manyBatches() {
        List<String> chunks = buildItems(1000);
        List<List<String>> batches = simulateInsertBatch(chunks, 256);
        assertThat(batches).hasSize(4); // 256+256+256+232 = 1000
        assertThat(batches.get(0)).hasSize(256);
        assertThat(batches.get(1)).hasSize(256);
        assertThat(batches.get(2)).hasSize(256);
        assertThat(batches.get(3)).hasSize(232);
    }

    @Test
    void insertBatch_exactMultiple_512BatchSize_2Batches() {
        List<String> chunks = buildItems(512);
        List<List<String>> batches = simulateInsertBatch(chunks, 256);
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(256);
        assertThat(batches.get(1)).hasSize(256);
    }

    // ---- Consistency: all items preserved across partitions ----

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 50, 100, 999})
    void allItemsPreserved_acrossAnyBatchSize(int totalItems) {
        for (int bs : new int[]{1, 3, 5, 10, 25, 50, 100, 256, 500}) {
            List<String> items = buildItems(totalItems);
            List<List<String>> batches = simulateEmbeddingBatch(items, bs);
            List<String> flattened = new ArrayList<>();
            for (List<String> b : batches) {
                flattened.addAll(b);
            }
            assertThat(flattened)
                    .as("batchSize=%d totalItems=%d", bs, totalItems)
                    .isEqualTo(items);
        }
    }

    private static List<String> buildItems(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }
}
