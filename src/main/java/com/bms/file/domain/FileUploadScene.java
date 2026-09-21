package com.bms.file.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 面向前端的文件上传业务场景。内部文件类别、权限和历史数据与此枚举解耦，上传接口仅接受这四个场景。
 */
public enum FileUploadScene {
    TASK_CREATION(FileCategory.PCB_SCHEMATIC, false),
    PROCESS_REVIEW(FileCategory.PROCESS, true),
    SCHEMATIC_REVIEW(FileCategory.PCB_SCHEMATIC, true),
    MUTUAL_CHECK_REVIEW(FileCategory.MUTUAL_CHECK_ATTACHMENT, true);

    private final FileCategory fileCategory;
    private final boolean requiresTask;

    FileUploadScene(FileCategory fileCategory, boolean requiresTask) {
        this.fileCategory = fileCategory;
        this.requiresTask = requiresTask;
    }

    public FileCategory fileCategory() {
        return fileCategory;
    }

    public boolean requiresTask() {
        return requiresTask;
    }
}
