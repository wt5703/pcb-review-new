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

/** 验证 Excel 下载响应使用二进制媒体类型，并为中文文件名提供标准的 RFC 5987 编码。 */
class TaskArchiveControllerTest {

    @Test
    void shouldReturnExcelDownloadResponseForOpinionExport() {
        TaskApplicationService taskApplicationService = mock(TaskApplicationService.class);
        TaskArchiveApplicationService taskArchiveApplicationService = mock(TaskArchiveApplicationService.class);
        TaskArchiveOpinionExportApplicationService exportApplicationService = mock(TaskArchiveOpinionExportApplicationService.class);
        when(exportApplicationService.export(101L)).thenReturn(
                new TaskArchiveOpinionExportApplicationService.ExportedExcel("BMS_控制板_评审意见.xlsx", new byte[] {1, 2, 3}));
        TaskArchiveController controller = new TaskArchiveController(taskApplicationService, taskArchiveApplicationService,
                exportApplicationService);

        ResponseEntity<byte[]> response = controller.exportOpinions(101L, "BMS001");

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
        assertThat(disposition).startsWith("attachment; filename=\"review-opinions.xlsx\"; filename*=UTF-8''")
                .doesNotContain("=?UTF-8?Q?", "?=");
    }
}
