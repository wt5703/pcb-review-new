package com.bms.review.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证检查项模板 Excel 导入能够按评审类型和检查项编码创建或更新模板，保证上传格式中的多条定义在同一事务内处理。
 */
class CheckItemTemplateImportApplicationServiceTest {
    private final CheckItemTemplateMapper templateMapper = mock(CheckItemTemplateMapper.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final CheckItemTemplateApplicationService service = new CheckItemTemplateApplicationService(templateMapper, auditMapper);

    @Test
    void shouldCreateAndUpdateTemplatesFromWorkbook() throws Exception {
        CheckItemTemplateRecord existing = new CheckItemTemplateRecord();
        existing.setId(11L);
        existing.setReviewType("PCB");
        existing.setItemKey("PCB-002");
        existing.setVersion(3L);
        when(templateMapper.nextId()).thenReturn(10L);
        when(templateMapper.findByReviewTypeAndItemKey("PCB", "PCB-001")).thenReturn(null);
        when(templateMapper.findByReviewTypeAndItemKey("PCB", "PCB-002")).thenReturn(existing);
        when(templateMapper.update(existing)).thenReturn(1);

        CheckItemTemplateApplicationService.ImportResult result = service.importWorkbook(workbookBytes(),
                new CurrentUser(1L, Set.of(Role.PCB_LEADER)));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(templateMapper).insert(any(CheckItemTemplateRecord.class));
        verify(templateMapper).update(existing);
    }

    private byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("检查项模板");
            var header = sheet.createRow(0);
            String[] headers = {"评审类型", "检查项编码", "父级检查项编码", "检查项名称", "排序号", "是否启用"};
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            writeRow(sheet.createRow(1), "PCB", "PCB-001", "", "电源间距检查", 1, "是");
            writeRow(sheet.createRow(2), "PCB", "PCB-002", "PCB-001", "滤波电容检查", 2, "否");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeRow(org.apache.poi.ss.usermodel.Row row, String reviewType, String itemKey, String parentItemKey,
                          String itemName, int sortNo, String enabled) {
        row.createCell(0).setCellValue(reviewType);
        row.createCell(1).setCellValue(itemKey);
        row.createCell(2).setCellValue(parentItemKey);
        row.createCell(3).setCellValue(itemName);
        row.createCell(4).setCellValue(sortNo);
        row.createCell(5).setCellValue(enabled);
    }
}
