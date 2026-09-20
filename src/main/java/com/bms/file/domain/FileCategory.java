package com.bms.file.domain;

import com.bms.identity.domain.Permission;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 定义评审任务文件的业务类别，并将每类文件映射到权限矩阵中的上传和下载功能权限，避免在接口层散落类别判断。
 */
public enum FileCategory {
    PCB_SCHEMATIC(Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE),
    PROCESS(Permission.UPLOAD_PROCESS_FILE, Permission.DOWNLOAD_PROCESS_FILE),
    STRUCTURE(Permission.UPLOAD_STRUCTURE_FILE, Permission.DOWNLOAD_STRUCTURE_FILE),
    MUTUAL_CHECK_ATTACHMENT(Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT),
    OPINION_ATTACHMENT(Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT);

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
