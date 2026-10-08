package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FileUploadRes {

    private String fileName;
    private String filePath;
    private Long fileSize;
    private String taskId;
    private boolean indexed;

    public FileUploadRes() {
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

}
