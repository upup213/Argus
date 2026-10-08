package org.example.task;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.IndexTask;
import org.example.service.VectorIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class IndexTaskManager {

    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final String DEFAULT_UPLOAD_PATH = "./uploads";

    private final ConcurrentHashMap<String, IndexTask> tasks = new ConcurrentHashMap<>();

    @Autowired
    @Qualifier("indexExecutor")
    private Executor indexExecutor;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${index.build.max-retries:2}")
    private int maxRetries;

    @Value("${file.upload.path:" + DEFAULT_UPLOAD_PATH + "}")
    private String uploadPath;

    /**
     * Submit a file index task. Returns taskId immediately.
     */
    public String submitFile(String filePath) {
        IndexTask task = IndexTask.pending(IndexTask.TaskType.FILE, filePath);
        tasks.put(task.getTaskId(), task);

        meterRegistry.counter("sba.index.task.active", "type", "FILE").increment();

        indexExecutor.execute(() -> runWithRetry(task.getTaskId()));
        return task.getTaskId();
    }

    /**
     * Submit a directory rebuild task. Returns taskId immediately.
     */
    public String submitRebuild(String directoryPath) {
        IndexTask task = IndexTask.pending(IndexTask.TaskType.DIRECTORY_REBUILD, directoryPath);
        tasks.put(task.getTaskId(), task);

        meterRegistry.counter("sba.index.task.active", "type", "DIRECTORY_REBUILD").increment();

        indexExecutor.execute(() -> runWithRetry(task.getTaskId()));
        return task.getTaskId();
    }

    /**
     * Run with retry logic for network-like exceptions.
     */
    private void runWithRetry(String taskId) {
        IndexTask task = getAndTransitionRunning(taskId);
        if (task == null) {
            return;
        }

        try {
            executeTask(task);
            transitionSuccess(task);
        } catch (Exception e) {
            if (isRetriable(e) && task.getRetryCount() < maxRetries) {
                backoffAndRetry(taskId);
            } else {
                transitionFailed(task, sanitizeFailReason(e.getMessage()));
            }
        } finally {
            cleanupTaskActiveMetric(task.getType());
        }
    }

    /**
     * Execute the actual indexing operation based on task type.
     */
    private void executeTask(IndexTask task) {
        switch (task.getType()) {
            case FILE -> vectorIndexService.indexSingleFile(task.getTarget());
            case DIRECTORY_REBUILD -> vectorIndexService.indexDirectory(task.getTarget());
        }
    }

    /**
     * Check if an exception is retriable (network-like).
     */
    private boolean isRetriable(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("TimeoutException")
                    || className.contains("ConnectException")
                    || className.contains("SocketException")
                    || className.contains("Connection refused")
                    || className.contains("InterruptedIOException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Exponential backoff retry.
     */
    private void backoffAndRetry(String taskId) {
        IndexTask task = tasks.get(taskId);
        if (task == null) {
            return;
        }

        long retryNum = task.getRetryCount() + 1;
        task.setRetryCount(retryNum);

        long backoffMs = Math.min(1000L * (1L << (retryNum - 1)), 30000L);
        log.info("Task {} failed (attempt {}/{}), backing off {}ms before retry",
                taskId, retryNum, maxRetries, backoffMs);

        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        // Re-enqueue via executor
        indexExecutor.execute(() -> runWithRetry(taskId));
    }

    /**
     * Transition task state to RUNNING atomically.
     */
    private IndexTask getAndTransitionRunning(String taskId) {
        AtomicReference<IndexTask> currentRef = new AtomicReference<>();
        tasks.compute(taskId, (key, existing) -> {
            if (existing == null || existing.getStatus() != IndexTask.TaskStatus.PENDING) {
                return existing;
            }
            IndexTask updated = new IndexTask();
            updated.setTaskId(taskId);
            updated.type = existing.getType();
            updated.target = existing.getTarget();
            updated.setStatus(IndexTask.TaskStatus.RUNNING);
            updated.setProgressDone(0);
            updated.setProgressTotal(1);
            updated.setStartTime(System.currentTimeMillis());
            updated.setRetryCount(existing.getRetryCount());
            currentRef.set(updated);
            return updated;
        });
        return currentRef.get();
    }

    /**
     * Transition task state to SUCCESS.
     */
    private void transitionSuccess(IndexTask task) {
        IndexTask updated = new IndexTask();
        updated.setTaskId(task.getTaskId());
        updated.type = task.getType();
        updated.target = task.getTarget();
        updated.setStatus(IndexTask.TaskStatus.SUCCESS);
        updated.setProgressDone(task.getProgressTotal() > 0 ? task.getProgressTotal() : 1);
        updated.setProgressTotal(task.getProgressTotal() > 0 ? task.getProgressTotal() : 1);
        updated.setStartTime(task.getStartTime());
        updated.setEndTime(System.currentTimeMillis());
        updated.setRetryCount(task.getRetryCount());

        tasks.put(task.getTaskId(), updated);
        log.info("Index task completed successfully: {}", task.getTaskId());
    }

    /**
     * Transition task state to FAILED.
     */
    private void transitionFailed(IndexTask task, String failReason) {
        IndexTask updated = new IndexTask();
        updated.setTaskId(task.getTaskId());
        updated.type = task.getType();
        updated.target = task.getTarget();
        updated.setStatus(IndexTask.TaskStatus.FAILED);
        updated.setFailReason(failReason);
        updated.setStartTime(task.getStartTime());
        updated.setEndTime(System.currentTimeMillis());
        updated.setRetryCount(task.getRetryCount());

        tasks.put(task.getTaskId(), updated);
        log.error("Index task failed: {}, reason: {}", task.getTaskId(), failReason);
    }

    /**
     * Get task by ID.
     */
    public IndexTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * List tasks with pagination (most recent first).
     */
    public List<IndexTask> listTasks(int page, int size) {
        List<IndexTask> allTasks = List.copyOf(tasks.values()).stream()
                .sorted(Comparator.comparingLong(IndexTask::getCreateTime).reversed())
                .skip((long) page * size)
                .limit(size)
                .toList();
        return allTasks;
    }

    /**
     * Cleanup active metric when task completes or fails.
     */
    private void cleanupTaskActiveMetric(IndexTask.TaskType type) {
        String tagKey = type.name();
        try {
            meterRegistry.counter("sba.index.task.active", "type", tagKey).increment(-1);
        } catch (IllegalArgumentException e) {
            // Counter may not exist if Micrometer registry doesn't support decrement; ignore
        }
    }

    /**
     * Sanitize failure reason to remove potentially sensitive info.
     */
    private String sanitizeFailReason(String rawMessage) {
        if (rawMessage == null) {
            return "unknown error";
        }
        // Strip control characters; max 512 chars
        String sanitized = rawMessage.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        return sanitized.length() > 512 ? sanitized.substring(0, 512) : sanitized;
    }

    /**
     * Scheduled rebuild. Enabled via cron property (disabled by default).
     */
    @Scheduled(cron = "${index.rebuild.cron:-}")
    public void scheduledRebuild() {
        String dirPath = uploadPath != null && !uploadPath.isEmpty() ? uploadPath : DEFAULT_UPLOAD_PATH;
        log.info("Scheduled directory rebuild triggered for: {}", dirPath);
        submitRebuild(dirPath);
    }

    /**
     * Startup compensation: re-enqueue PENDING/RUNNING tasks from last shutdown.
     */
    @PostConstruct
    public void startupCompensation() {
        List<IndexTask> incompleteTasks = tasks.values().stream()
                .filter(t -> t.getStatus() == IndexTask.TaskStatus.PENDING
                        || t.getStatus() == IndexTask.TaskStatus.RUNNING)
                .toList();

        if (!incompleteTasks.isEmpty()) {
            log.info("Startup compensation: re-enqueueing {} incomplete tasks", incompleteTasks.size());
            for (IndexTask task : incompleteTasks) {
                // Re-enqueue only if still present (not overwritten by newer submission)
                if (tasks.containsKey(task.getTaskId())) {
                    indexExecutor.execute(() -> runWithRetry(task.getTaskId()));
                }
            }
        }
    }
}
