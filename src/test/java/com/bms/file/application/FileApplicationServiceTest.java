package com.bms.file.application;

import com.bms.file.domain.FileCategory;
import com.bms.file.domain.FileUploadScene;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.file.infrastructure.PendingFileUploadMapper;
import com.bms.file.infrastructure.PendingFileUploadRecord;
import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.task.domain.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 验证文件应用服务对公司资源服务调用后的文件登记、当前文件替换和下载授权规则，不依赖真实文件或数据库。
 */
class FileApplicationServiceTest {
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper accessMapper = mock(TaskAssignmentAccessMapper.class);
    private final PendingFileUploadMapper pendingFileUploadMapper = mock(PendingFileUploadMapper.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final ResourceServiceClient resourceServiceClient = mock(ResourceServiceClient.class);
    private final FileApplicationService service = new FileApplicationService(fileMapper, taskMapper, accessMapper,
            resourceServiceClient, pendingFileUploadMapper, outboxEventPublisher, auditMapper);
    private final CurrentUser designer = new CurrentUser(10L, Set.of(Role.DESIGNER));

    @Test
    void shouldReplaceCurrentFileWhenBusinessFileKeyIsUploadedAgain() {
        when(fileMapper.nextId()).thenReturn(101L, 102L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(resourceServiceClient.upload(any(MultipartFile.class), eq(1L), eq(FileCategory.TASK_CREATION)))
                .thenReturn(new ResourceServiceClient.StoredResource("/company/BMS.pcb", "/company/BMS.pcb"));
        FileApplicationService.FileView first = service.uploadMultipartFile(1L, FileCategory.TASK_CREATION,
                "BMS-P1", multipart("BMS.pcb", "first"), designer);

        ReviewFileRecord latest = record(101L, "md5-a");
        when(fileMapper.findLatest(1L, FileCategory.TASK_CREATION.name(), "BMS-P1")).thenReturn(latest);
        FileApplicationService.FileView second = service.uploadMultipartFile(1L, FileCategory.TASK_CREATION,
                "BMS-P1", multipart("BMS-v2.pcb", "second"), designer);

        assertThat(first.id()).isEqualTo(101L);
        assertThat(second.id()).isEqualTo(101L);
        verify(fileMapper).updateCurrent(any(ReviewFileRecord.class));
        verify(fileMapper).insert(any(ReviewFileRecord.class));
        verify(auditMapper, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    void shouldAllowEmcExpertAndRejectHardwareExpertWhenDownloadingPcbFile() {
        ReviewFileRecord file = record(101L, "md5-a");
        when(fileMapper.findById(101L)).thenReturn(file);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(resourceServiceClient.download("BMS.pcb", "mock-file-1")).thenReturn(new byte[] { 1 });

        FileApplicationService.DownloadContent download = service.downloadTaskFile(1L, 101L,
                new CurrentUser(20L, Set.of(Role.EMC_EXPERT)));

        assertThat(download.fileName()).isEqualTo("BMS.pcb");
        assertThatThrownBy(() -> service.downloadTaskFile(1L, 101L, new CurrentUser(21L, Set.of(Role.HARDWARE_EXPERT))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("无对应文件操作权限");
    }

    @Test
    void shouldRejectUploadForFinishedTask() {
        ReviewTaskRecord finished = taskRecord();
        finished.setStatus(TaskStatus.FINISHED.name());
        when(taskMapper.findById(1L)).thenReturn(finished);

        assertThatThrownBy(() -> service.uploadAndRegister(1L, FileCategory.TASK_CREATION, multipart("BMS.pcb", "content"), designer))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("已结束任务不允许上传新文件");
    }

    @Test
    void shouldRejectAnotherDesignerUploadingPcbFile() {
        when(taskMapper.findById(1L)).thenReturn(taskRecord());

        assertThatThrownBy(() -> service.uploadAndRegister(1L, FileCategory.TASK_CREATION,
                multipart("BMS.pcb", "content"), new CurrentUser(11L, Set.of(Role.DESIGNER))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("仅任务设计者可以上传该任务的 PCB 或原理图文件");
    }

    @Test
    void shouldRejectSavingSamePendingFileUuidToTaskAgain() {
        String fileId = "b4466fe0-2c68-44b5-92d2-100000000001";
        PendingFileUploadRecord pending = new PendingFileUploadRecord();
        pending.setFileId(fileId); pending.setFileCategory(FileCategory.TASK_CREATION.name()); pending.setFileName("BMS.pcb");
        pending.setFileSize(100L); pending.setMd5("md5-a"); pending.setResourcePath("/company/BMS.pcb"); pending.setUploadedBy(10L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(pendingFileUploadMapper.findByFileId(fileId)).thenReturn(pending);
        when(fileMapper.findLatest(1L, FileCategory.TASK_CREATION.name(), fileId)).thenReturn(record(101L, "md5-a"));

        assertThatThrownBy(() -> service.bindPendingInitialFiles(1L, java.util.List.of(fileId), designer))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("该任务已保存对应文件，不能重复保存");
    }

    @Test
    void shouldRegisterStageFileAfterResourceUpload() {
        when(fileMapper.nextId()).thenReturn(102L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());

        FileApplicationService.FileView uploaded = service.registerStageFile(1L, FileUploadScene.PROCESS_REVIEW,
                new FileApplicationService.FileReferenceCommand("/bms/pcb/process.zip", "process.zip", 3L, "md5", null), designer);

        assertThat(uploaded.category()).isEqualTo(FileCategory.PROCESS_REVIEW);
        assertThat(uploaded.businessFileKey()).isEqualTo("PROCESS_REVIEW");
        verify(fileMapper).insert(any(ReviewFileRecord.class));
        verify(auditMapper).insert(any());
    }

    @Test
    void shouldRejectTaskCreationSceneWhenRegisteringStageFile() {
        assertThatThrownBy(() -> service.registerStageFile(1L, FileUploadScene.TASK_CREATION,
                new FileApplicationService.FileReferenceCommand("/bms/pcb/design.pcb", "design.pcb", 1L, null, null), designer))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("创建任务文件应通过任务保存或提交接口关联");
    }

    private ReviewFileRecord record(long id, String md5) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id);
        record.setTaskId(1L);
        record.setFileCategory(FileCategory.TASK_CREATION.name());
        record.setBusinessFileKey("BMS-P1");
        record.setFileName("BMS.pcb");
        record.setFileSize(100L);
        record.setMd5(md5);
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

    private MultipartFile multipart(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
