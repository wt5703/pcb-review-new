package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.archive.application.TaskArchiveOpinionExportApplicationService;
import com.bms.task.application.TaskApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证中文 Excel 文件名仅使用 RFC 5987 filename*，避免浏览器下载名称混入 MIME 编码文本。 */
class TaskArchiveControllerTest {

    @Test
    void shouldUseOnlyRfc5987EncodedFileNameForOpinionExport() {
        TaskApplicationService taskApplicationService = mock(TaskApplicationService.class);
        TaskArchiveApplicationService taskArchiveApplicationService = mock(TaskArchiveApplicationService.class);
        TaskArchiveOpinionExportApplicationService exportApplicationService = mock(TaskArchiveOpinionExportApplicationService.class);
        when(exportApplicationService.export(101L)).thenReturn(
                new TaskArchiveOpinionExportApplicationService.ExportedExcel("BMS_控制板_评审意见.xlsx", new byte[] {1, 2, 3}));
        TaskArchiveController controller = new TaskArchiveController(taskApplicationService, taskArchiveApplicationService,
                exportApplicationService);

        ResponseEntity<byte[]> response = controller.exportOpinions(101L, "BMS001");

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("attachment; filename*=UTF-8''")
                .doesNotContain("filename=", "=?UTF-8?Q?", "?=");
    }
}
