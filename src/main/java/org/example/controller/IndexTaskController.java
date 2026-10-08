package org.example.controller;

import lombok.Getter;
import lombok.Setter;
import org.example.common.ApiResponse;
import org.example.dto.IndexTask;
import org.example.task.IndexTaskManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/index/tasks")
public class IndexTaskController {

    @Autowired
    private IndexTaskManager taskManager;

    /**
     * POST /api/index/tasks - Trigger directory rebuild.
     * Body (optional): {"directoryPath":"..."} or no body (uses default upload path).
     */
    @PostMapping
    public ResponseEntity<?> triggerRebuild(@RequestBody(required = false) RebuildRequest request) {
        String directoryPath = (request != null && request.directoryPath != null)
                ? request.directoryPath : null;
        String taskId = taskManager.submitRebuild(directoryPath);
        return ResponseEntity.ok(ApiResponse.success(new TaskResult(taskId)));
    }

    /**
     * GET /api/index/tasks/{taskId} - Get task status by ID.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        IndexTask task = taskManager.getTask(taskId);
        if (task == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Task not found"));
        }
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    /**
     * GET /api/index/tasks - List tasks with pagination (recent 50 by default).
     */
    @GetMapping
    public ResponseEntity<?> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<IndexTask> tasks = taskManager.listTasks(page, Math.min(size, 50));
        if (tasks.isEmpty()) {
            tasks = Collections.emptyList();
        }
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @Getter
    @Setter
    public static class RebuildRequest {
        private String directoryPath;
    }

    @Getter
    @Setter
    public static class TaskResult {
        private String taskId;

        public TaskResult(String taskId) {
            this.taskId = taskId;
        }
    }
}
