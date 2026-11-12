package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import com.bms.task.domain.ReviewType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证导入以类别名称和 parentId 建模，不需要检查项编码。 */
class CheckItemTemplateImportApplicationServiceTest {
    private final CheckItemTemplateMapper templateMapper = mock(CheckItemTemplateMapper.class);
    private final CheckItemTemplateApplicationService service = new CheckItemTemplateApplicationService(templateMapper);

    @Test
    void shouldSplitLineStartedParenthesizedNumbersWithoutBreakingDecimals() throws Exception {
        when(templateMapper.nextId()).thenReturn(10L, 11L, 12L, 13L, 14L, 15L, 16L);

        CheckItemTemplateApplicationService.ImportResult result = service.importWorkbook(workbookBytes(
                        "螺丝孔",
                        "1）孔边缘与线间距＞15mil\n"
                                + "2）如800v高压螺丝孔隔离间距pad到shape禁步区≥10mm\n"
                                + "3）边缘定位柱（金属化孔、金属定位柱）与PAD边缘<1.5mm，需增加阻焊丝印；\n"
                                + "4）从板螺丝孔螺母柱(螺母柱直径10mm）以螺丝孔中心画圆禁布区直径12.5mm以上、到器件禁布区直径15mm以上。\n"
                                + "5\\)板子中间的螺丝附件器件与板子垂直放置"),
                ReviewType.SCHEMATIC);

        ArgumentCaptor<CheckItemTemplateRecord> records = ArgumentCaptor.forClass(CheckItemTemplateRecord.class);
        verify(templateMapper, atLeastOnce()).insert(records.capture());
        List<CheckItemTemplateRecord> inserted = records.getAllValues();

        assertThat(result.totalRows()).isEqualTo(7);
        assertThat(inserted.get(0).getItemName()).isEqualTo("检查项模板");
        assertThat(inserted.get(1).getItemName()).isEqualTo("螺丝孔");
        assertThat(inserted.get(1).getParentId()).isEqualTo(10L);
        assertThat(inserted.get(0).getReviewType()).isEqualTo(ReviewType.SCHEMATIC.name());
        assertThat(inserted.get(0).getParentId()).isNull();
        assertThat(inserted.get(0).getSortNo()).isEqualTo(1);
        assertThat(inserted.stream().skip(2).toList()).allSatisfy(record -> assertThat(record.getParentId()).isEqualTo(11L));
        assertThat(inserted.stream().skip(2).map(CheckItemTemplateRecord::getSortNo).toList()).containsExactly(1, 2, 3, 4, 5);
        assertThat(inserted).extracting(CheckItemTemplateRecord::getItemName).contains(
                "孔边缘与线间距＞15mil",
                "如800v高压螺丝孔隔离间距pad到shape禁步区≥10mm",
                "边缘定位柱（金属化孔、金属定位柱）与PAD边缘<1.5mm，需增加阻焊丝印；",
                "从板螺丝孔螺母柱(螺母柱直径10mm）以螺丝孔中心画圆禁布区直径12.5mm以上、到器件禁布区直径15mm以上。",
                "板子中间的螺丝附件器件与板子垂直放置");
    }

    @Test
    void shouldRejectImportWhenTemplateAlreadyExistsForReviewType() throws Exception {
        CheckItemTemplateRecord existing = new CheckItemTemplateRecord();
        existing.setId(1L);
        when(templateMapper.findAll(ReviewType.PCB.name())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.importWorkbook(workbookBytes("已有类别", "已有检查项"), ReviewType.PCB))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode()).isEqualTo(ErrorCode.DUPLICATE_REQUEST))
                .hasMessage("PCB互检单模板已存在，请确认后重新导入");
    }

    @Test
    void shouldReplaceExistingTemplateOnlyAfterConfirmed() throws Exception {
        CheckItemTemplateRecord existing = new CheckItemTemplateRecord();
        existing.setId(1L);
        when(templateMapper.findAll(ReviewType.PCB.name())).thenReturn(List.of(existing));
        when(templateMapper.disableEnabledByReviewType(ReviewType.PCB.name())).thenReturn(1);
        when(templateMapper.nextId()).thenReturn(10L, 11L, 12L);

        CheckItemTemplateApplicationService.ImportResult result = service.importWorkbook(
                workbookBytes("新类别", "新检查项"), ReviewType.PCB, true);

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.replacedCount()).isEqualTo(1);
        verify(templateMapper).disableEnabledByReviewType(ReviewType.PCB.name());
    }

    @Test
    void shouldIgnorePlaceholderItemsAndRemoveDisplaySerialNumbers() throws Exception {
        when(templateMapper.nextId()).thenReturn(10L, 11L, 12L);

        CheckItemTemplateApplicationService.ImportResult result = service.importWorkbook(workbookBytes(
                        "时钟", "8.时钟/数据走线是否等长、远离干扰，隔离带是否保持净空\n...\n…\n9、时钟线不要跨分割区域"),
                ReviewType.PCB);

        ArgumentCaptor<CheckItemTemplateRecord> records = ArgumentCaptor.forClass(CheckItemTemplateRecord.class);
        verify(templateMapper, atLeastOnce()).insert(records.capture());
        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(records.getAllValues()).extracting(CheckItemTemplateRecord::getItemName).containsExactly(
                "检查项模板", "时钟", "时钟/数据走线是否等长、远离干扰，隔离带是否保持净空", "时钟线不要跨分割区域");
    }

    @Test
    void shouldKeepSheetsSeparateAndAllowAnEmptySheet() throws Exception {
        when(templateMapper.nextId()).thenReturn(10L, 11L, 12L, 13L, 14L, 15L, 16L);
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String name : List.of("BMU", "BSU")) {
                var sheet = workbook.createSheet(name);
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("检查项类别");
                header.createCell(1).setCellValue("检查内容");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("连接器");
                row.createCell(1).setCellValue("检查安装位置");
            }
            workbook.createSheet("分流器");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(service.importWorkbook(bytes, ReviewType.SCHEMATIC).createdCount()).isEqualTo(7);
        ArgumentCaptor<CheckItemTemplateRecord> records = ArgumentCaptor.forClass(CheckItemTemplateRecord.class);
        verify(templateMapper, atLeastOnce()).insert(records.capture());
        assertThat(records.getAllValues()).extracting(CheckItemTemplateRecord::getParentId)
                .containsExactly(null, 10L, 11L, null, 13L, 14L, null);
    }

    private byte[] workbookBytes(String category, String items) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("检查项模板");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("类别");
            header.createCell(1).setCellValue("检查项");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(category);
            row.createCell(1).setCellValue(items);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
