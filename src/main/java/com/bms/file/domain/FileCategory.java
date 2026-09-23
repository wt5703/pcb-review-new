package com.bms.file.domain;

import com.bms.identity.domain.Permission;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 定义评审任务文件的业务类别，并将每类文件映射到权限矩阵中的上传和下载功能权限，避免在接口层散落类别判断。
 */
public enum FileCategory {
    TASK_CREATION(Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE),
    PCB_REVIEW(Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE),
    SCHEMATIC_REVIEW(Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE),
    MUTUAL_CHECK_REVIEW(Permission.UPLOAD_MUTUAL_CHECK_FILE, Permission.DOWNLOAD_MUTUAL_CHECK_FILE),
    PROCESS_REVIEW(Permission.UPLOAD_PROCESS_FILE, Permission.DOWNLOAD_PROCESS_FILE),
    STRUCTURE_REVIEW(Permission.UPLOAD_STRUCTURE_FILE, Permission.DOWNLOAD_STRUCTURE_FILE);

    private final Permission uploadPermission;
    private final Permission downloadPermission;

    FileCategory(Permission uploadPermission, Permission downloadPermission) {
        this.uploadPermission = uploadPermission;
        this.downloadPermission = downloadPermission;
    }

    public Permission uploadPermission() {
        return uploadPermission;
    }

    public Permission downloadPermission() {
        return downloadPermission;
    }
}
