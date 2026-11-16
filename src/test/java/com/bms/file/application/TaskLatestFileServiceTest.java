package com.bms.file.application;

import com.bms.file.domain.FileCategory;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskLatestFileServiceTest {
    private final FileApplicationService files = mock(FileApplicationService.class);
    private final UserCenterUserProfileClient users = mock(UserCenterUserProfileClient.class);
    private final TaskLatestFileService service = new TaskLatestFileService(files, users);

    @Test
    void includesAllLatestBatchFilesAcrossCategoriesAndResolvesNames() {
        when(files.listLatestByCategory(1L, FileCategory.PCB_REVIEW, "viewer"))
                .thenReturn(List.of(file(1L, FileCategory.PCB_REVIEW), file(2L, FileCategory.PCB_REVIEW)));
        when(files.listLatestByCategory(1L, FileCategory.PCB_STRUCTURE_REVIEW, "viewer"))
                .thenReturn(List.of(file(3L, FileCategory.PCB_STRUCTURE_REVIEW)));
        when(users.getByEmployeeNos(anyList())).thenReturn(Map.of("u1",
                new UserCenterUserProfileClient.UserProfile("u1", "张工", null, null, null)));
        var result = service.list(1L, "viewer");
        assertThat(result).extracting(TaskLatestFileService.TaskFileView::id).containsExactly(1L, 2L, 3L);
        assertThat(result).extracting(TaskLatestFileService.TaskFileView::uploadedByName).containsOnly("张工");
        for (FileCategory category : FileCategory.values()) verify(files).listLatestByCategory(1L, category, "viewer");
    }

    @Test
    void returnsEmptyWhenNoFilesExist() {
        assertThat(service.list(1L, "viewer")).isEmpty();
    }

    @Test
    void propagatesAccessFailureBeforeLookingUpNames() {
        when(files.listLatestByCategory(1L, FileCategory.PCB_REVIEW, "viewer"))
                .thenThrow(new com.bms.common.BusinessException(com.bms.common.ErrorCode.FORBIDDEN, "无权访问"));
        assertThatThrownBy(() -> service.list(1L, "viewer")).hasMessage("无权访问");
        verifyNoInteractions(users);
    }

    private FileApplicationService.FileView file(long id, FileCategory category) {
        return new FileApplicationService.FileView(id, 1L, category, "设计.pdf", "pdf", 10L, null,
                "uuid-" + id, null, "u1", java.time.LocalDateTime.of(2026, 11, 16, 10, 30), "DRAFT", true);
    }
}
