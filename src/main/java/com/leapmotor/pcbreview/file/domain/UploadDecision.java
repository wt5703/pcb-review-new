package com.leapmotor.pcbreview.file.domain;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 封装一次文件上传的版本判定结果，明确文件是否重复、是否应创建新版本以及调用方应采用的处理分支。
 */


public record UploadDecision(boolean duplicate, int versionNo) {

    public static UploadDecision duplicate(int versionNo) {
        return new UploadDecision(true, versionNo);
    }

    public static UploadDecision newVersion(int versionNo) {
        return new UploadDecision(false, versionNo);
    }
}
