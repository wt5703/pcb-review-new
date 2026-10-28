package com.bms.archive.application;

import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.review.domain.OpinionSeverity;
import com.bms.review.domain.OpinionSourceType;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 验证归档意见导出按角色拆分工作表，并将同一意见的多轮设计者答复展开为多行。
 */
class TaskArchiveOpinionExportApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final UserCenterUserProfileClient userProfileClient = mock(UserCenterUserProfileClient.class);
    private final TaskArchiveOpinionExportApplicationService service = new TaskArchiveOpinionExportApplicationService(taskMapper, opinionMapper, userProfileClient);

    @Test
    void shouldExportOneSheetPerRoleAndOneRowPerReply() throws Exception {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(101L); task.setProjectName("BMS"); task.setDesignName("控制板"); task.setStatus("FINISHED");
        task.setReviewerAssignments("EMC_EXPERT:BMS010;STRUCTURE_EXPERT:BMS011");
        ReviewOpinionRecord emcOpinion = opinion(1L, OpinionSourceType.PCB_REVIEW, "BMS010", "王工", OpinionSeverity.SERIOUS,
                "<p>CANH<br/>CANL</p><img src='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9J6v8AAAAASUVORK5CYII=' />", "CAN 总线问题");
        ReviewOpinionRecord structureOpinion = opinion(2L, OpinionSourceType.PCB_STRUCTURE_REVIEW, "BMS011", "张工", OpinionSeverity.GENERAL,
                "<p>结构位置第一行<br/>结构位置第二行<br/>结构位置第三行<br/>结构位置第四行</p>", "结构问题");
        OpinionReplyRecord firstReply = reply(21L, "ACCEPT", "已调整"); OpinionReplyRecord secondReply = reply(22L, "REJECT", "保留原设计");
        OpinionConfirmationRecord confirmation = new OpinionConfirmationRecord(); confirmation.setId(31L); confirmation.setReplyId(21L); confirmation.setPassed(true); confirmation.setComment("确认通过");
        when(taskMapper.findById(101L)).thenReturn(task);
        when(opinionMapper.findByTaskId(101L)).thenReturn(List.of(emcOpinion, structureOpinion));
        when(opinionMapper.findRepliesByOpinionId(1L)).thenReturn(List.of(firstReply, secondReply));
        when(opinionMapper.findConfirmationsByOpinionId(1L)).thenReturn(List.of(confirmation));
        when(opinionMapper.findRepliesByOpinionId(2L)).thenReturn(List.of());
        when(opinionMapper.findConfirmationsByOpinionId(2L)).thenReturn(List.of());

        TaskArchiveOpinionExportApplicationService.ExportedExcel exported = service.export(101L);

        assertThat(exported.fileName()).isEqualTo("BMSPCB评审检查表.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var emc = workbook.getSheet("EMC评审"); var structure = workbook.getSheet("结构评审");
            assertThat(emc).isNotNull(); assertThat(structure).isNotNull();
            assertThat(emc.getRow(1).getCell(0).getStringCellValue()).isEqualTo("EMC评审人");
            assertThat(emc.getRow(1).getCell(1).getStringCellValue()).isEqualTo("王工");
            assertThat(emc.getMergedRegions()).contains(new CellRangeAddress(1, 1, 1, 7));
            assertThat(emc.getRow(2).getCell(0).getStringCellValue()).isEqualTo("序号");
            assertThat(emc.getRow(3).getCell(1).getStringCellValue()).isEqualTo("专家评审");
            assertThat(emc.getRow(3).getCell(2).getStringCellValue()).contains("CANH", "[图片]");
            assertThat(emc.getRow(3).getHeightInPoints()).isGreaterThan(48F);
            assertThat(workbook.getAllPictures()).hasSize(2);
            assertThat(emc.getRow(3).getCell(6).getStringCellValue()).isEqualTo("接受并修改：已调整");
            assertThat(emc.getRow(3).getCell(7).getStringCellValue()).isEqualTo("确认通过：确认通过");
            assertThat(emc.getRow(4).getCell(6).getStringCellValue()).isEqualTo("不接受：保留原设计");
            assertThat(emc.getRow(4).getCell(7).getStringCellValue()).isEqualTo("待专家确认");
            assertThat(structure.getRow(1).getCell(0).getStringCellValue()).isEqualTo("结构评审人");
            assertThat(structure.getRow(1).getCell(1).getStringCellValue()).isEqualTo("张工");
            assertThat(structure.getRow(3).getCell(1).getStringCellValue()).isEqualTo("结构评审");
            assertThat(structure.getRow(3).getHeightInPoints()).isGreaterThan(48F);
            assertThat(structure.getRow(3).getCell(6).getStringCellValue()).isEqualTo("待设计者答复");
        }
        verifyNoInteractions(userProfileClient);
    }

    @Test
    void shouldUseProjectNameAndSchematicReviewTypeForExportFileName() {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(102L);
        task.setProjectName("某某某项目");
        task.setDesignName("主控原理图");
        task.setDesignerEmployeeNo("BMS001");
        task.setDesignerName("设计者");
        task.setReviewType("SCHEMATIC");
        task.setStatus("FINISHED");
        when(taskMapper.findById(102L)).thenReturn(task);
        // 即使底层实现异常地返回 null，也应生成可打开的空检查表，而不是返回错误文本。
        when(opinionMapper.findByTaskId(102L)).thenReturn(null);

        TaskArchiveOpinionExportApplicationService.ExportedExcel exported = service.export(102L);

        assertThat(exported.fileName()).isEqualTo("某某某项目原理图评审检查表.xlsx");
        assertThat(exported.content()[0]).isEqualTo((byte) 'P');
        assertThat(exported.content()[1]).isEqualTo((byte) 'K');
    }

    private ReviewOpinionRecord opinion(long id, OpinionSourceType sourceType, String raisedByEmployeeNo, String raisedByName,
                                        OpinionSeverity severity, String richText, String comment) {
        ReviewOpinionRecord record = new ReviewOpinionRecord();
        record.setId(id); record.setSourceType(sourceType); record.setRaisedByEmployeeNo(raisedByEmployeeNo); record.setRaisedByName(raisedByName); record.setSeverity(severity); record.setRichText(richText); record.setComment(comment);
        return record;
    }

    private OpinionReplyRecord reply(long id, String type, String reason) {
        OpinionReplyRecord record = new OpinionReplyRecord(); record.setId(id); record.setReplyType(type); record.setReason(reason); return record;
    }
}
