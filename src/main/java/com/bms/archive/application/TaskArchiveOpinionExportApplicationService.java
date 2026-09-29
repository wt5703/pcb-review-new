package com.bms.archive.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 导出已归档任务的专家评审意见。按评审角色拆分工作表，每个设计者答复占一行，便于线下评审记录留档。
 */
@Service
public class TaskArchiveOpinionExportApplicationService {
    private static final List<String> SHEET_ROLE_ORDER = List.of(
            ReviewRole.HARDWARE_EXPERT.name(), ReviewRole.EMC_EXPERT.name(), ReviewRole.PCB_EXPERT.name(),
            ReviewRole.PROCESS_EXPERT.name(), ReviewRole.STRUCTURE_EXPERT.name());
    private static final String UNCLASSIFIED_EXPERT_ROLE = "EXPERT_REVIEW";
    private static final String[] HEADERS = {"序号", "评审阶段", "位置", "问题描述", "提出人", "严重等级", "处理情况", "提出人确认"};
    private final ReviewTaskMapper taskMapper;
    private final ReviewOpinionMapper opinionMapper;

    public TaskArchiveOpinionExportApplicationService(ReviewTaskMapper taskMapper, ReviewOpinionMapper opinionMapper) {
        this.taskMapper = taskMapper;
        this.opinionMapper = opinionMapper;
    }

    public ExportedExcel export(long taskId) {
        ReviewTaskRecord task = requireFinishedTask(taskId);
        Map<String, List<ExportRow>> rowsByRole = exportRows(task);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (rowsByRole.isEmpty()) createSheet(workbook, "专家评审", task, List.of());
            else rowsByRole.forEach((role, rows) -> createSheet(workbook, sheetName(role), task, rows));
            workbook.write(output);
            return new ExportedExcel(fileName(task), output.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "生成评审意见 Excel 失败");
        }
    }

    private Map<String, List<ExportRow>> exportRows(ReviewTaskRecord task) {
        Map<Long, Set<ReviewRole>> rolesByReviewer = rolesByReviewer(task.getReviewerAssignments());
        Map<String, List<ExportRow>> result = new LinkedHashMap<>();
        opinionMapper.findByTaskId(task.getId()).stream().filter(this::isExpertOpinion)
                .filter(opinion -> !"PASS".equalsIgnoreCase(opinion.getSeverity()))
                .sorted(Comparator.comparing(ReviewOpinionRecord::getId)).forEach(opinion ->
                        rolesForOpinion(opinion, rolesByReviewer).forEach(role -> result.computeIfAbsent(role, ignored -> new ArrayList<>()).addAll(rowsForOpinion(opinion))));
        Map<String, List<ExportRow>> ordered = new LinkedHashMap<>();
        SHEET_ROLE_ORDER.stream().filter(result::containsKey).forEach(role -> ordered.put(role, result.get(role)));
        result.forEach((role, rows) -> ordered.putIfAbsent(role, rows));
        return ordered;
    }

    private boolean isExpertOpinion(ReviewOpinionRecord opinion) {
        return "EXPERT_REVIEW".equals(opinion.getSourceType()) || "SCHEMATIC_REVIEW".equals(opinion.getSourceType())
                || "PROCESS_REVIEW".equals(opinion.getSourceType()) || "STRUCTURE_REVIEW".equals(opinion.getSourceType());
    }

    private Set<String> rolesForOpinion(ReviewOpinionRecord opinion, Map<Long, Set<ReviewRole>> rolesByReviewer) {
        if ("PROCESS_REVIEW".equals(opinion.getSourceType())) return Set.of(ReviewRole.PROCESS_EXPERT.name());
        if ("STRUCTURE_REVIEW".equals(opinion.getSourceType())) return Set.of(ReviewRole.STRUCTURE_EXPERT.name());
        Set<ReviewRole> roles = new LinkedHashSet<>(rolesByReviewer.getOrDefault(opinion.getRaisedBy(), Set.of()));
        roles.removeIf(role -> role != ReviewRole.HARDWARE_EXPERT && role != ReviewRole.EMC_EXPERT && role != ReviewRole.PCB_EXPERT);
        return roles.isEmpty() ? Set.of(UNCLASSIFIED_EXPERT_ROLE) : roles.stream().map(Enum::name).collect(Collectors.toSet());
    }

    private List<ExportRow> rowsForOpinion(ReviewOpinionRecord opinion) {
        List<OpinionReplyRecord> replies = opinionMapper.findRepliesByOpinionId(opinion.getId());
        List<OpinionConfirmationRecord> confirmationRecords = opinionMapper.findConfirmationsByOpinionId(opinion.getId());
        Map<Long, OpinionConfirmationRecord> confirmations = (confirmationRecords == null ? List.<OpinionConfirmationRecord>of() : confirmationRecords).stream()
                .filter(confirmation -> confirmation.getReplyId() != null).collect(Collectors.toMap(OpinionConfirmationRecord::getReplyId, item -> item,
                        (left, right) -> left.getId() >= right.getId() ? left : right));
        if (replies == null || replies.isEmpty()) return List.of(row(opinion, "待设计者答复", "待专家确认"));
        return replies.stream().map(reply -> row(opinion, handlingName(reply), confirmationName(confirmations.get(reply.getId())))).toList();
    }

    private ExportRow row(ReviewOpinionRecord opinion, String handling, String confirmation) {
        return new ExportRow(phaseName(opinion.getSourceType()), richTextToPlainText(opinion.getRichText()), opinion.getComment(),
                raisedByName(opinion), severityName(opinion.getSeverity()), handling, confirmation);
    }

    private Map<Long, Set<ReviewRole>> rolesByReviewer(String persistedAssignments) {
        Map<Long, Set<ReviewRole>> result = new LinkedHashMap<>();
        for (TaskReviewerAssignment assignment : TaskReviewerAssignmentCodec.decode(persistedAssignments)) {
            for (Long reviewerId : assignment.reviewerIds()) result.computeIfAbsent(reviewerId, ignored -> new LinkedHashSet<>()).add(assignment.reviewRole());
        }
        return result;
    }

    private void createSheet(XSSFWorkbook workbook, String sheetName, ReviewTaskRecord task, List<ExportRow> rows) {
        var sheet = workbook.createSheet(sheetName); sheet.setDisplayGridlines(false);
        int[] widths = {8, 15, 40, 40, 14, 12, 38, 28}; for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
        CellStyle titleStyle = titleStyle(workbook), headerStyle = headerStyle(workbook), bodyStyle = bodyStyle(workbook);
        var titleRow = sheet.createRow(0); titleRow.setHeightInPoints(28); Cell title = titleRow.createCell(0);
        title.setCellValue(task.getProjectName() + "设计评审记录表--" + sheetName); title.setCellStyle(titleStyle); sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
        var reviewerRow = sheet.createRow(1); reviewerRow.createCell(0).setCellValue(sheetName + "人"); styleRow(reviewerRow, headerStyle);
        var headerRow = sheet.createRow(3); for (int index = 0; index < HEADERS.length; index++) write(headerRow, index, HEADERS[index], headerStyle);
        for (int index = 0; index < rows.size(); index++) {
            ExportRow row = rows.get(index); var excelRow = sheet.createRow(index + 4); excelRow.setHeightInPoints(48);
            write(excelRow, 0, index + 1, bodyStyle); write(excelRow, 1, row.phase(), bodyStyle); write(excelRow, 2, row.location(), bodyStyle); write(excelRow, 3, row.description(), bodyStyle);
            write(excelRow, 4, row.raisedByName(), bodyStyle); write(excelRow, 5, row.severity(), bodyStyle); write(excelRow, 6, row.handling(), bodyStyle); write(excelRow, 7, row.confirmation(), bodyStyle);
        }
        sheet.setAutoFilter(new CellRangeAddress(3, Math.max(4, rows.size() + 3), 0, HEADERS.length - 1)); sheet.createFreezePane(0, 4);
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor((short) 17); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = workbook.createFont(); font.setBold(true); font.setFontHeightInPoints((short) 16); font.setColor((short) 9); style.setFont(font); return style;
    }
    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor((short) 42); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true); setBorders(style);
        XSSFFont font = workbook.createFont(); font.setBold(true); style.setFont(font); return style;
    }
    private CellStyle bodyStyle(XSSFWorkbook workbook) { CellStyle style = workbook.createCellStyle(); style.setVerticalAlignment(VerticalAlignment.TOP); style.setWrapText(true); setBorders(style); return style; }
    private void setBorders(CellStyle style) { style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN); style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN); }
    private void styleRow(org.apache.poi.ss.usermodel.Row row, CellStyle style) { for (int index = 0; index < HEADERS.length; index++) { Cell cell = row.getCell(index); if (cell == null) cell = row.createCell(index); cell.setCellStyle(style); } }
    private void write(org.apache.poi.ss.usermodel.Row row, int column, Object value, CellStyle style) { Cell cell = row.createCell(column); cell.setCellValue(value == null ? "" : String.valueOf(value)); cell.setCellStyle(style); }

    private ReviewTaskRecord requireFinishedTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId); if (task == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        if (!TaskStatus.FINISHED.name().equals(task.getStatus())) throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务尚未结束，不能导出归档意见"); return task;
    }
    private String sheetName(String role) {
        return java.util.Arrays.stream(ReviewRole.values()).filter(value -> value.name().equals(role)).findFirst()
                .map(ReviewRole::displayName).orElse("专家评审");
    }
    private String phaseName(String sourceType) { return switch (sourceType) { case "PROCESS_REVIEW" -> "工艺评审"; case "STRUCTURE_REVIEW" -> "结构评审"; default -> "专家评审"; }; }
    private String severityName(String severity) { return switch (severity == null ? "" : severity) { case "SERIOUS" -> "严重"; case "MINOR" -> "轻微"; case "GENERAL" -> "一般"; default -> severity == null || severity.isBlank() ? "一般" : severity; }; }
    private String handlingName(OpinionReplyRecord reply) { String action = switch (reply.getReplyType()) { case "ACCEPT" -> "接受并修改"; case "ACCEPT_NO_CHANGE" -> "接受不修改"; case "REJECT" -> "不接受"; default -> reply.getReplyType(); }; return reply.getReason() == null || reply.getReason().isBlank() ? action : action + "：" + reply.getReason(); }
    private String confirmationName(OpinionConfirmationRecord confirmation) { if (confirmation == null) return "待专家确认"; String result = Boolean.TRUE.equals(confirmation.getPassed()) ? "确认通过" : "确认不通过"; return confirmation.getComment() == null || confirmation.getComment().isBlank() ? result : result + "：" + confirmation.getComment(); }
    private String raisedByName(ReviewOpinionRecord opinion) { return opinion.getRaisedByName() == null || opinion.getRaisedByName().isBlank() ? "用户#" + opinion.getRaisedBy() : opinion.getRaisedByName(); }
    private String richTextToPlainText(String richText) { if (richText == null || richText.isBlank()) return ""; return richText.replaceAll("(?i)<br\\s*/?>", "\\n").replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\\n").replaceAll("(?s)<img[^>]*>", "[图片]").replaceAll("(?s)<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("\\n{3,}", "\\n\\n").trim(); }
    private String fileName(ReviewTaskRecord task) { return (task.getProjectName() + "_" + task.getDesignName() + "_评审意见").replaceAll("[\\\\/:*?\"<>|]", "_") + ".xlsx"; }

    public record ExportedExcel(String fileName, byte[] content) { }
    private record ExportRow(String phase, String location, String description, String raisedByName, String severity, String handling, String confirmation) { }
}
