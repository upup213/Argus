package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IndexTask {

    public enum TaskType {
        FILE, DIRECTORY_REBUILD
    }

    public enum TaskStatus {
        PENDING, RUNNING, SUCCESS, FAILED
    }

    private String taskId;
    private TaskType type;
    private String target;
    private TaskStatus status;
    private int progressDone;
    private int progressTotal;
    private String failReason;
    private long retryCount;
    private long createTime;
    private long startTime;
    private long endTime;

    // Default constructor (no-op, Lombok generates getter/setter above)

    public static IndexTask pending(TaskType type, String target) {
        IndexTask task = new IndexTask();
        task.setTaskId(java.util.UUID.randomUUID().toString());
        task.type = type;
        task.target = target;
        task.status = TaskStatus.PENDING;
        task.progressDone = 0;
        task.progressTotal = 0;
        task.retryCount = 0;
        task.createTime = System.currentTimeMillis();
        return task;
    }

    public static IndexTask running(String taskId, String target) {
        IndexTask task = new IndexTask();
        task.setTaskId(taskId);
        task.type = TaskType.FILE;
        task.target = target;
        task.status = TaskStatus.RUNNING;
        task.progressDone = 0;
        task.progressTotal = 1;
        task.startTime = System.currentTimeMillis();
        return task;
    }

    public static IndexTask success(String taskId, String target) {
        IndexTask task = new IndexTask();
        task.setTaskId(taskId);
        task.type = TaskType.FILE;
        task.target = target;
        task.status = TaskStatus.SUCCESS;
        task.endTime = System.currentTimeMillis();
        return task;
    }

    public static IndexTask failed(String target, String reason) {
        IndexTask task = new IndexTask();
        task.setTaskId(java.util.UUID.randomUUID().toString());
        task.type = TaskType.FILE;
        task.target = target;
        task.status = TaskStatus.FAILED;
        task.failReason = reason;
        task.endTime = System.currentTimeMillis();
        return task;
    }
}
