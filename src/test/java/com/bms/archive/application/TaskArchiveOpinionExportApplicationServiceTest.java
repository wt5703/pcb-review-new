package com.bms.archive.application;

import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 验证归档意见导出按角色拆分工作表，并将同一意见的多轮设计者答复展开为多行。
 */
class TaskArchiveOpinionExportApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final TaskArchiveOpinionExportApplicationService service = new TaskArchiveOpinionExportApplicationService(taskMapper, opinionMapper);

    @Test
    void shouldExportOneSheetPerRoleAndOneRowPerReply() throws Exception {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(101L); task.setProjectName("BMS"); task.setDesignName("控制板"); task.setStatus("FINISHED");
        task.setReviewerAssignments("EMC_EXPERT:10;STRUCTURE_EXPERT:11");
        ReviewOpinionRecord emcOpinion = opinion(1L, "EXPERT_REVIEW", 10L, "王工", "SERIOUS", "<p>CANH<br/>CANL</p><img src='data:image/png;base64,a' />", "CAN 总线问题");
        ReviewOpinionRecord structureOpinion = opinion(2L, "STRUCTURE_REVIEW", 11L, "张工", "GENERAL", "结构位置", "结构问题");
        OpinionReplyRecord firstReply = reply(21L, "ACCEPT", "已调整"); OpinionReplyRecord secondReply = reply(22L, "REJECT", "保留原设计");
        OpinionConfirmationRecord confirmation = new OpinionConfirmationRecord(); confirmation.setId(31L); confirmation.setReplyId(21L); confirmation.setPassed(true); confirmation.setComment("确认通过");
        when(taskMapper.findById(101L)).thenReturn(task);
        when(opinionMapper.findByTaskId(101L)).thenReturn(List.of(emcOpinion, structureOpinion));
        when(opinionMapper.findRepliesByOpinionId(1L)).thenReturn(List.of(firstReply, secondReply));
        when(opinionMapper.findConfirmationsByOpinionId(1L)).thenReturn(List.of(confirmation));
        when(opinionMapper.findRepliesByOpinionId(2L)).thenReturn(List.of());
        when(opinionMapper.findConfirmationsByOpinionId(2L)).thenReturn(List.of());

        TaskArchiveOpinionExportApplicationService.ExportedExcel exported = service.export(101L);

        assertThat(exported.fileName()).isEqualTo("BMS_控制板_评审意见.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var emc = workbook.getSheet("EMC评审"); var structure = workbook.getSheet("结构评审");
            assertThat(emc).isNotNull(); assertThat(structure).isNotNull();
            assertThat(emc.getRow(4).getCell(1).getStringCellValue()).isEqualTo("专家评审");
            assertThat(emc.getRow(4).getCell(2).getStringCellValue()).contains("CANH", "[图片]");
            assertThat(emc.getRow(4).getCell(6).getStringCellValue()).isEqualTo("接受并修改：已调整");
            assertThat(emc.getRow(4).getCell(7).getStringCellValue()).isEqualTo("确认通过：确认通过");
            assertThat(emc.getRow(5).getCell(6).getStringCellValue()).isEqualTo("不接受：保留原设计");
            assertThat(emc.getRow(5).getCell(7).getStringCellValue()).isEqualTo("待专家确认");
            assertThat(structure.getRow(4).getCell(1).getStringCellValue()).isEqualTo("结构评审");
            assertThat(structure.getRow(4).getCell(6).getStringCellValue()).isEqualTo("待设计者答复");
        }
    }

    private ReviewOpinionRecord opinion(long id, String sourceType, long raisedBy, String raisedByName, String severity, String richText, String comment) {
        ReviewOpinionRecord record = new ReviewOpinionRecord();
        record.setId(id); record.setSourceType(sourceType); record.setRaisedBy(raisedBy); record.setRaisedByName(raisedByName); record.setSeverity(severity); record.setRichText(richText); record.setComment(comment);
        return record;
    }

    private OpinionReplyRecord reply(long id, String type, String reason) {
        OpinionReplyRecord record = new OpinionReplyRecord(); record.setId(id); record.setReplyType(type); record.setReason(reason); return record;
    }
}
