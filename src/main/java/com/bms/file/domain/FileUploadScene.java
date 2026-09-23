package com.bms.file.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 流程登记文件时使用的业务场景。内部文件类别、权限和历史数据与此枚举解耦；文件上传接口仍以 FileCategory 作为参数。
 */
public enum FileUploadScene {
    TASK_CREATION(FileCategory.TASK_CREATION, false),
    PCB_REVIEW(FileCategory.PCB_REVIEW, true),
    PROCESS_REVIEW(FileCategory.PROCESS_REVIEW, true),
    SCHEMATIC_REVIEW(FileCategory.SCHEMATIC_REVIEW, true),
    MUTUAL_CHECK_REVIEW(FileCategory.MUTUAL_CHECK_REVIEW, true);

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
