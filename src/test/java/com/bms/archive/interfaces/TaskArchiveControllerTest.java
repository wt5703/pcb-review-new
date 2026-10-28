package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.archive.application.TaskArchiveOpinionExportApplicationService;
import com.bms.task.application.TaskApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 Excel 下载响应使用二进制媒体类型，并兼容不同下载端解析中文文件名。 */
class TaskArchiveControllerTest {

    @Test
    void shouldReturnExcelDownloadResponseForOpinionExport() {
        TaskApplicationService taskApplicationService = mock(TaskApplicationService.class);
        TaskArchiveApplicationService taskArchiveApplicationService = mock(TaskArchiveApplicationService.class);
        TaskArchiveOpinionExportApplicationService exportApplicationService = mock(TaskArchiveOpinionExportApplicationService.class);
        when(exportApplicationService.export(101L)).thenReturn(
                new TaskArchiveOpinionExportApplicationService.ExportedExcel("22PCB评审检查表.xlsx", new byte[] {1, 2, 3}));
        TaskArchiveController controller = new TaskArchiveController(taskApplicationService, taskArchiveApplicationService,
                exportApplicationService);

        ResponseEntity<byte[]> response = controller.exportOpinions(101L);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
        assertThat(disposition).isEqualTo(ContentDisposition.attachment()
                .filename("22PCB评审检查表.xlsx")
                .build()
                .toString());
        assertThat(disposition).doesNotContain("filename*", "=?UTF-8?Q?");
        assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo("22PCB评审检查表.xlsx");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo(HttpHeaders.CONTENT_DISPOSITION);
        verifyNoInteractions(taskApplicationService, taskArchiveApplicationService);
    }

    @Test
    void shouldWriteRawXlsxBytesInsteadOfJsonOrText() throws Exception {
        TaskArchiveOpinionExportApplicationService exportApplicationService = mock(TaskArchiveOpinionExportApplicationService.class);
        byte[] xlsxBytes = new byte[] {'P', 'K', 3, 4};
        when(exportApplicationService.export(101L)).thenReturn(
                new TaskArchiveOpinionExportApplicationService.ExportedExcel("BMS项目PCB评审检查表.xlsx", xlsxBytes));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskArchiveController(mock(TaskApplicationService.class),
                mock(TaskArchiveApplicationService.class), exportApplicationService)).build();

        MvcResult result = mvc.perform(get("/tasks/{taskId}/archive/export", 101L)
                        .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(xlsxBytes);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").toString());
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(ContentDisposition.attachment()
                        .filename("BMS项目PCB评审检查表.xlsx")
                        .build()
                        .toString());
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo(HttpHeaders.CONTENT_DISPOSITION);
    }
}
