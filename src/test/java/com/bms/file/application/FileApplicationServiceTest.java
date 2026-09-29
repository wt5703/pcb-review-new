package com.bms.file.application;

import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 验证文件应用服务对公司资源服务调用后的独立文件登记和下载授权规则，不依赖真实文件或数据库。
 */
class FileApplicationServiceTest {
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper accessMapper = mock(TaskAssignmentAccessMapper.class);
    private final ResourceServiceClient resourceServiceClient = mock(ResourceServiceClient.class);
    private final FileApplicationService service = new FileApplicationService(fileMapper, taskMapper, accessMapper,
            resourceServiceClient);
    private final CurrentUser designer = new CurrentUser(10L, Set.of(Role.DESIGNER));

    @Test
    void shouldRegisterEachUploadedFileIndependently() {
        when(fileMapper.nextId()).thenReturn(101L, 102L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(resourceServiceClient.upload(any(MultipartFile.class), anyString()))
                .thenReturn(new ResourceServiceClient.StoredResource("/company/BMS.pcb", "/company/BMS.pcb"));
        FileApplicationService.FileView first = service.upload(1L, FileCategory.PCB_REVIEW,
                multipart("BMS.pcb", "first"), designer);

        FileApplicationService.FileView second = service.upload(1L, FileCategory.PCB_REVIEW,
                multipart("BMS-v2.pcb", "second"), designer);

        assertThat(first.id()).isEqualTo(101L);
        assertThat(second.id()).isEqualTo(102L);
        verify(fileMapper, org.mockito.Mockito.times(2)).markLatestAsHistorical(1L, FileCategory.PCB_REVIEW.name());
        verify(fileMapper, org.mockito.Mockito.times(2)).insert(any(ReviewFileRecord.class));
    }

    @Test
    void shouldAllowEmcExpertAndRejectHardwareExpertWhenDownloadingPcbFile() {
        ReviewFileRecord file = record(101L, "md5-a");
        when(fileMapper.findById(101L)).thenReturn(file);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(resourceServiceClient.download("BMS.pcb", "mock-file-1")).thenReturn(new byte[] { 1 });

        FileApplicationService.DownloadContent download = service.downloadFile(101L,
                new CurrentUser(20L, Set.of(Role.EMC_EXPERT)));

        assertThat(download.fileName()).isEqualTo("BMS.pcb");
        assertThatThrownBy(() -> service.downloadFile(101L, new CurrentUser(21L, Set.of(Role.HARDWARE_EXPERT))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("无对应文件操作权限");
    }

    @Test
    void shouldRejectUploadForFinishedTask() {
        ReviewTaskRecord finished = taskRecord();
        finished.setStatus(TaskStatus.FINISHED.name());
        when(taskMapper.findById(1L)).thenReturn(finished);

        assertThatThrownBy(() -> service.upload(1L, FileCategory.PCB_REVIEW, multipart("BMS.pcb", "content"), designer))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("已结束任务不允许上传新文件");
    }

    @Test
    void shouldRejectAnotherDesignerUploadingPcbFile() {
        when(taskMapper.findById(1L)).thenReturn(taskRecord());

        assertThatThrownBy(() -> service.upload(1L, FileCategory.PCB_REVIEW,
                multipart("BMS.pcb", "content"), new CurrentUser(11L, Set.of(Role.DESIGNER))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("仅任务设计者可以上传该任务的 PCB 或原理图文件");
    }

    @Test
    void shouldReuseFileAlreadyBoundToCurrentTask() {
        String fileId = "b4466fe0-2c68-44b5-92d2-100000000001";
        ReviewFileRecord pending = new ReviewFileRecord();
        pending.setFileId(fileId); pending.setFileCategory(FileCategory.PCB_REVIEW.name()); pending.setFileName("BMS.pcb");
        pending.setFileSize(100L); pending.setMd5("md5-a"); pending.setResourcePath("/company/BMS.pcb"); pending.setUploadedBy(10L);
        pending.setTaskId(1L);
        when(taskMapper.findById(1L)).thenReturn(taskRecord());
        when(fileMapper.findByFileId(fileId)).thenReturn(pending);

        assertThat(service.bindPendingInitialFiles(1L, java.util.List.of(fileId), designer))
                .singleElement()
                .satisfies(file -> assertThat(file.fileId()).isEqualTo(fileId));
    }

    private ReviewFileRecord record(long id, String md5) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id);
        record.setTaskId(1L);
        record.setFileCategory(FileCategory.PCB_REVIEW.name());
        record.setFileName("BMS.pcb");
        record.setFileSize(100L);
        record.setMd5(md5);
        record.setFileId("mock-file-1");
        record.setResourcePath("mock-file-1");
        record.setLatest(true);
        record.setUploadedBy(10L);
        return record;
    }

    private ReviewTaskRecord taskRecord() {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(1L);
        record.setReviewType("PCB");
        record.setDesignerId(10L);
        return record;
    }

    private MultipartFile multipart(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
