package com.bms.file.application;

import com.bms.file.domain.FileCategory;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 汇总任务各文件类型的最新批次，并补充上传者姓名。 */
@Service
public class TaskLatestFileService {
    private final FileApplicationService files;
    private final UserCenterUserProfileClient users;

    public TaskLatestFileService(FileApplicationService files, UserCenterUserProfileClient users) {
        this.files = files;
        this.users = users;
    }

    /**
     * 按文件类别分组返回任务各评审阶段当前最新版本文件；即使某个类别没有文件，也会以空列表返回，方便前端按类别渲染。
     */
    public Map<FileCategory, List<TaskFileView>> list(long taskId, String employeeNo) {
        Map<FileCategory, List<FileApplicationService.FileView>> latestByCategory = new EnumMap<>(FileCategory.class);
        List<FileApplicationService.FileView> latest = new ArrayList<>();
        // 复用文件查询权限与版本规则；一个类别最新批次中的多个文件全部保留。
        for (FileCategory category : FileCategory.values()) {
            List<FileApplicationService.FileView> filesOfCategory = files.listLatestByCategory(taskId, category, employeeNo);
            latestByCategory.put(category, filesOfCategory);
            latest.addAll(filesOfCategory);
        }
        var profiles = users.getByEmployeeNos(latest.stream().map(FileApplicationService.FileView::uploadedByEmployeeNo).toList());
        Map<FileCategory, List<TaskFileView>> grouped = new EnumMap<>(FileCategory.class);
        for (FileCategory category : FileCategory.values()) {
            grouped.put(category, latestByCategory.get(category).stream().map(file -> {
                var profile = profiles.get(file.uploadedByEmployeeNo());
                return new TaskFileView(file.id(), file.fileId(), file.fileName(), file.category(), file.uploadedAt(),
                        file.uploadedByEmployeeNo(), profile == null ? file.uploadedByEmployeeNo() : profile.displayName(), file.uploadedStage());
            }).toList());
        }
        return grouped;
    }

    @Schema(description = "任务各评审阶段当前最新版本文件信息")
    public record TaskFileView(
            @Schema(description = "任务文件记录主键，下载接口使用此 ID") Long id,
            @Schema(description = "资源服务文件 UUID") String fileId,
            @Schema(description = "文件名称，包含扩展名") String fileName,
            @Schema(description = "文件类型：PCB_REVIEW PCB评审、PCB_PROCESS_REVIEW 工艺评审、PCB_STRUCTURE_REVIEW 结构评审、SCHEMATIC_REVIEW 原理图评审") FileCategory fileCategory,
            @Schema(description = "文件上传时间", example = "2026-11-16 10:30:00") @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime uploadedAt,
            @Schema(description = "上传者工号") String uploadedByEmployeeNo,
            @Schema(description = "上传者姓名，用户中心无资料时回退为工号") String uploadedByName,
            @Schema(description = "上传时所在任务阶段编码") String uploadedStage) { }
}
