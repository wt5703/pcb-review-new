package com.leapmotor.pcbreview.file.application;

import com.leapmotor.pcbreview.file.domain.FileCategory;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.notification.application.OutboxEventPublisher;
import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 验证文件应用服务对上传会话、MD5 去重和版本递增的编排规则，使用 Mock 文件服务且不依赖真实文件或数据库。
 */
class FileApplicationServiceTest {
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper accessMapper = mock(TaskAssignmentAccessMapper.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final com.leapmotor.pcbreview.file.infrastructure.MockFileStorage storage = new com.leapmotor.pcbreview.file.infrastructure.MockFileStorage();
    private final FileApplicationService service = new FileApplicationService(fileMapper, taskMapper, accessMapper, storage,
            outboxEventPublisher, auditMapper);
    private final CurrentUser designer = new CurrentUser(10L, Set.of(Role.DESIGNER));

    @Test
    void shouldCreateFirstVersionAndIncrementWhenMd5Changes() {
        when(fileMapper.nextId()).thenReturn(101L, 102L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        FileApplicationService.UploadSessionView firstSession = service.createUploadSession(1L, FileCategory.PCB_SCHEMATIC, designer);
        FileApplicationService.FileView first = service.register(new FileApplicationService.RegisterFileCommand(1L,
                firstSession.uploadSessionId(), FileCategory.PCB_SCHEMATIC, "BMS-P1", "BMS.pcb", 100L, "md5-a"), designer);

        ReviewFileRecord latest = record(101L, "md5-a", 1);
        when(fileMapper.findLatest(1L, FileCategory.PCB_SCHEMATIC.name(), "BMS-P1")).thenReturn(latest);
        FileApplicationService.UploadSessionView secondSession = service.createUploadSession(1L, FileCategory.PCB_SCHEMATIC, designer);
        FileApplicationService.FileView second = service.register(new FileApplicationService.RegisterFileCommand(1L,
                secondSession.uploadSessionId(), FileCategory.PCB_SCHEMATIC, "BMS-P1", "BMS-v2.pcb", 110L, "md5-b"), designer);

        assertThat(first.versionNo()).isEqualTo(1);
        assertThat(second.versionNo()).isEqualTo(2);
        verify(fileMapper).markLatestAsHistorical(1L, FileCategory.PCB_SCHEMATIC.name(), "BMS-P1");
        verify(fileMapper, org.mockito.Mockito.times(2)).insert(any(ReviewFileRecord.class));
        verify(auditMapper, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    void shouldAllowEmcExpertAndRejectHardwareExpertWhenDownloadingPcbFile() {
        ReviewFileRecord file = record(101L, "md5-a", 1);
        when(fileMapper.findById(101L)).thenReturn(file);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());

        FileApplicationService.DownloadView download = service.requestDownload(101L,
                new CurrentUser(20L, Set.of(Role.EMC_EXPERT)));

        assertThat(download.downloadUrl()).contains("mock-file-1");
        assertThatThrownBy(() -> service.requestDownload(101L, new CurrentUser(21L, Set.of(Role.HARDWARE_EXPERT))))
                .isInstanceOf(com.leapmotor.pcbreview.common.BusinessException.class)
                .hasMessage("无对应文件操作权限");
    }

    @Test
    void shouldRejectUploadForFinishedTask() {
        ReviewTaskRecord finished = taskRecord();
        finished.setStatus(TaskStatus.FINISHED.name());
        when(taskMapper.findById(1L)).thenReturn(finished);

        assertThatThrownBy(() -> service.createUploadSession(1L, FileCategory.PCB_SCHEMATIC, designer))
                .isInstanceOf(com.leapmotor.pcbreview.common.BusinessException.class)
                .hasMessage("已结束任务不允许上传新文件");
    }

    @Test
    void shouldRejectAnotherDesignerUploadingPcbFile() {
        when(taskMapper.findById(1L)).thenReturn(taskRecord());

        assertThatThrownBy(() -> service.createUploadSession(1L, FileCategory.PCB_SCHEMATIC,
                new CurrentUser(11L, Set.of(Role.DESIGNER))))
                .isInstanceOf(com.leapmotor.pcbreview.common.BusinessException.class)
                .hasMessage("仅任务设计者可以上传该任务的 PCB 或原理图文件");
    }

    private ReviewFileRecord record(long id, String md5, int versionNo) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id);
        record.setTaskId(1L);
        record.setFileCategory(FileCategory.PCB_SCHEMATIC.name());
        record.setBusinessFileKey("BMS-P1");
        record.setFileName("BMS.pcb");
        record.setFileSize(100L);
        record.setMd5(md5);
        record.setVersionNo(versionNo);
        record.setCompanyFileId("mock-file-1");
        record.setLatest(true);
        record.setUploadedBy(10L);
        return record;
    }

    private ReviewTaskRecord taskRecord() {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(1L);
        record.setDesignerId(10L);
        return record;
    }
}
