package com.bms.file.domain;

import java.util.Objects;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 根据文件内容摘要判断上传文件是否与现有版本重复；内容变化时生成新的版本决策，不直接负责文件二进制存储或下载授权。
 */


public final class FileVersionPolicy {

    public UploadDecision decide(String latestMd5, String uploadedMd5, int latestVersionNo) {
        if (latestVersionNo < 1) {
            throw new IllegalArgumentException("最新版本号必须大于零");
        }
        if (Objects.equals(latestMd5, uploadedMd5)) {
            return UploadDecision.duplicate(latestVersionNo);
        }
        return UploadDecision.newVersion(latestVersionNo + 1);
    }
}
