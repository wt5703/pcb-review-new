package com.bms.archive.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.review.domain.OpinionSeverity;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 导出已归档任务的专家评审意见。PCB 按评审角色拆分工作表；原理图使用“评审问题记录”模板；每个设计者答复占一行，便于线下评审记录留档。
 */
@Service
public class TaskArchiveOpinionExportApplicationService {
    private static final List<String> SHEET_ROLE_ORDER = List.of(
            ReviewRole.HARDWARE_EXPERT.name(), ReviewRole.EMC_EXPERT.name(), ReviewRole.PCB_EXPERT.name(),
            ReviewRole.PROCESS_EXPERT.name(), ReviewRole.STRUCTURE_EXPERT.name());
    private static final Set<OpinionSourceType> EXPERT_OPINION_SOURCE_TYPES = EnumSet.of(
            OpinionSourceType.PCB_REVIEW,
            OpinionSourceType.SCHEMATIC_REVIEW,
            OpinionSourceType.PCB_PROCESS_REVIEW,
            OpinionSourceType.PCB_STRUCTURE_REVIEW);
    private static final String[] HEADERS = {"序号", "评审阶段", "位置", "问题描述", "提出人", "严重等级", "处理情况", "提出人确认"};
    private static final String[] SCHEMATIC_HEADERS = {"评审问题序号", "评审问题提出时间", "评审对象", "问题描述", "问题等级", "原因分析",
            "处理措施", "计划解决时间", "实际解决时间", "责任人", "状态", "备注"};
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d");
    private static final Pattern DATA_IMAGE_PATTERN = Pattern.compile("(?i)data:image/([a-z0-9.+-]+);base64,([a-z0-9+/=]+)");
    private static final int LOCATION_COLUMN = 2;
    private static final float MAX_LOCATION_ROW_HEIGHT_POINTS = 400F;
    private static final float DEFAULT_IMAGE_HEIGHT_POINTS = 160F;
    private final ReviewTaskMapper taskMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final UserCenterUserProfileClient userProfileClient;

    public TaskArchiveOpinionExportApplicationService(ReviewTaskMapper taskMapper, ReviewOpinionMapper opinionMapper,
                                                       UserCenterUserProfileClient userProfileClient) {
        this.taskMapper = taskMapper;
        this.opinionMapper = opinionMapper;
        this.userProfileClient = userProfileClient;
    }

    public ExportedExcel export(long taskId) {
        ReviewTaskRecord task = requireFinishedTask(taskId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (ReviewType.SCHEMATIC.name().equals(task.getReviewType())) {
                createSchematicSheet(workbook, task, exportSchematicRows(task));
            } else {
                Map<String, List<ExportRow>> rowsByRole = exportRows(task);
                if (rowsByRole.isEmpty()) createSheet(workbook, "专家评审", task, List.of());
                else rowsByRole.forEach((role, rows) -> createSheet(workbook, sheetName(role), task, rows));
            }
            workbook.write(output);
            return new ExportedExcel(fileName(task), output.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "生成评审意见 Excel 失败");
        }
    }

    private Map<String, List<ExportRow>> exportRows(ReviewTaskRecord task) {
        Map<String, List<ExportRow>> result = new LinkedHashMap<>();
        taskOpinions(task.getId()).stream().filter(this::isExpertOpinion)
                .filter(opinion -> !opinion.isNoOpinion())
                .sorted(Comparator.comparing(ReviewOpinionRecord::getId)).forEach(opinion ->
                        result.computeIfAbsent(rolesForOpinion(opinion).name(), ignored -> new ArrayList<>()).addAll(rowsForOpinion(opinion)));
        Map<String, List<ExportRow>> ordered = new LinkedHashMap<>();
        SHEET_ROLE_ORDER.stream().filter(result::containsKey).forEach(role -> ordered.put(role, result.get(role)));
        result.forEach((role, rows) -> ordered.putIfAbsent(role, rows));
        return ordered;
    }

    /** 原理图归档固定使用用户提供的“评审问题记录”单 Sheet 模板，只导出原理图专家意见。 */
    private List<SchematicExportRow> exportSchematicRows(ReviewTaskRecord task) {
        String reviewObject = task.getProjectName() + "项目_" + task.getDesignName();
        String responsiblePerson = employeeName(task.getDesignerEmployeeNo(), task.getDesignerName());
        return taskOpinions(task.getId()).stream()
                .filter(opinion -> opinion.getSourceType() == OpinionSourceType.SCHEMATIC_REVIEW)
                .filter(opinion -> !opinion.isNoOpinion())
                .sorted(Comparator.comparing(ReviewOpinionRecord::getId))
                .flatMap(opinion -> schematicRowsForOpinion(opinion, reviewObject, responsiblePerson).stream())
                .toList();
    }

    private List<SchematicExportRow> schematicRowsForOpinion(ReviewOpinionRecord opinion, String reviewObject,
                                                              String responsiblePerson) {
        List<OpinionReplyRecord> replies = opinionMapper.findRepliesByOpinionId(opinion.getId());
        Map<Long, OpinionConfirmationRecord> confirmations = confirmationsByReply(opinion.getId());
        if (replies == null || replies.isEmpty()) {
            return List.of(new SchematicExportRow(opinion.getCreatedAt(), reviewObject, opinion.getComment(),
                    severityName(opinion.getSeverity()), "", "待设计者答复", null, responsiblePerson, "Open", "待专家确认"));
        }
        return replies.stream().map(reply -> {
            OpinionConfirmationRecord confirmation = confirmations.get(reply.getId());
            return new SchematicExportRow(opinion.getCreatedAt(), reviewObject, opinion.getComment(),
                    severityName(opinion.getSeverity()), "", handlingName(reply), reply.getCreatedAt(), responsiblePerson,
                    Boolean.TRUE.equals(confirmation == null ? null : confirmation.getPassed()) ? "Close" : "Open",
                    confirmationName(confirmation));
        }).toList();
    }

    private boolean isExpertOpinion(ReviewOpinionRecord opinion) {
        return EXPERT_OPINION_SOURCE_TYPES.contains(opinion.getSourceType());
    }

    /** MyBatis 正常返回空集合；这里额外兼容测试桩或历史实现返回 null 的情况。 */
    private List<ReviewOpinionRecord> taskOpinions(long taskId) {
        List<ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(taskId);
        return opinions == null ? List.of() : opinions;
    }

    private ReviewRole rolesForOpinion(ReviewOpinionRecord opinion) {
        return switch (opinion.getSourceType()) {
            case PCB_REVIEW -> ReviewRole.EMC_EXPERT;
            case SCHEMATIC_REVIEW -> ReviewRole.HARDWARE_EXPERT;
            case PCB_PROCESS_REVIEW -> ReviewRole.PROCESS_EXPERT;
            case PCB_STRUCTURE_REVIEW -> ReviewRole.STRUCTURE_EXPERT;
            case MUTUAL_CHECK_ITEM, MUTUAL_EXTRA -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检意见不支持归档专家意见导出");
        };
    }

    private List<ExportRow> rowsForOpinion(ReviewOpinionRecord opinion) {
        List<OpinionReplyRecord> replies = opinionMapper.findRepliesByOpinionId(opinion.getId());
        Map<Long, OpinionConfirmationRecord> confirmations = confirmationsByReply(opinion.getId());
        if (replies == null || replies.isEmpty()) return List.of(row(opinion, "待设计者答复", "待专家确认"));
        return replies.stream().map(reply -> row(opinion, handlingName(reply), confirmationName(confirmations.get(reply.getId())))).toList();
    }

    private Map<Long, OpinionConfirmationRecord> confirmationsByReply(long opinionId) {
        List<OpinionConfirmationRecord> confirmationRecords = opinionMapper.findConfirmationsByOpinionId(opinionId);
        return (confirmationRecords == null ? List.<OpinionConfirmationRecord>of() : confirmationRecords).stream()
                .filter(confirmation -> confirmation.getReplyId() != null)
                .collect(Collectors.toMap(OpinionConfirmationRecord::getReplyId, item -> item,
                        (left, right) -> left.getId() >= right.getId() ? left : right));
    }

    private ExportRow row(ReviewOpinionRecord opinion, String handling, String confirmation) {
        return new ExportRow(phaseName(opinion.getSourceType()), richTextContent(opinion.getRichText()), opinion.getComment(),
                raisedByName(opinion), severityName(opinion.getSeverity()), handling, confirmation);
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
            write(excelRow, 0, index + 1, bodyStyle); write(excelRow, 1, row.phase(), bodyStyle);
            writeRichTextLocation(workbook, sheet, excelRow, row.location(), bodyStyle);
            write(excelRow, 3, row.description(), bodyStyle);
            write(excelRow, 4, row.raisedByName(), bodyStyle); write(excelRow, 5, row.severity(), bodyStyle); write(excelRow, 6, row.handling(), bodyStyle); write(excelRow, 7, row.confirmation(), bodyStyle);
        }
        sheet.setAutoFilter(new CellRangeAddress(3, Math.max(4, rows.size() + 3), 0, HEADERS.length - 1)); sheet.createFreezePane(0, 4);
    }

    private void createSchematicSheet(XSSFWorkbook workbook, ReviewTaskRecord task, List<SchematicExportRow> rows) {
        var sheet = workbook.createSheet("评审问题记录");
        sheet.setDisplayGridlines(false);
        int[] widths = {17, 18, 30, 42, 14, 32, 32, 18, 18, 16, 12, 28};
        for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
        CellStyle titleStyle = schematicTitleStyle(workbook), headerStyle = headerStyle(workbook), bodyStyle = bodyStyle(workbook);
        var titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(28);
        Cell title = titleRow.createCell(0);
        title.setCellValue("评审问题记录");
        title.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, SCHEMATIC_HEADERS.length - 1));
        var headerRow = sheet.createRow(1);
        for (int index = 0; index < SCHEMATIC_HEADERS.length; index++) write(headerRow, index, SCHEMATIC_HEADERS[index], headerStyle);
        for (int index = 0; index < rows.size(); index++) {
            SchematicExportRow row = rows.get(index);
            var excelRow = sheet.createRow(index + 2);
            excelRow.setHeightInPoints(48);
            write(excelRow, 0, index + 1, bodyStyle);
            write(excelRow, 1, formatDate(row.raisedAt()), bodyStyle);
            write(excelRow, 2, row.reviewObject(), bodyStyle);
            write(excelRow, 3, row.description(), bodyStyle);
            write(excelRow, 4, row.severity(), bodyStyle);
            write(excelRow, 5, row.causeAnalysis(), bodyStyle);
            write(excelRow, 6, row.handlingMeasure(), bodyStyle);
            write(excelRow, 7, "", bodyStyle);
            write(excelRow, 8, formatDate(row.resolvedAt()), bodyStyle);
            write(excelRow, 9, row.responsiblePerson(), bodyStyle);
            write(excelRow, 10, row.status(), bodyStyle);
            write(excelRow, 11, row.remark(), bodyStyle);
        }
        sheet.setAutoFilter(new CellRangeAddress(1, Math.max(2, rows.size() + 1), 0, SCHEMATIC_HEADERS.length - 1));
        sheet.createFreezePane(0, 2);
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor((short) 17); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = workbook.createFont(); font.setBold(true); font.setFontHeightInPoints((short) 16); font.setColor((short) 9); style.setFont(font); return style;
    }
    private CellStyle schematicTitleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = workbook.createFont(); font.setBold(true); font.setFontHeightInPoints((short) 16); style.setFont(font); return style;
    }
    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor((short) 42); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true); setBorders(style);
        XSSFFont font = workbook.createFont(); font.setBold(true); style.setFont(font); return style;
    }
    private CellStyle bodyStyle(XSSFWorkbook workbook) { CellStyle style = workbook.createCellStyle(); style.setVerticalAlignment(VerticalAlignment.TOP); style.setWrapText(true); setBorders(style); return style; }
    private void setBorders(CellStyle style) { style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN); style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN); }
    private void styleRow(org.apache.poi.ss.usermodel.Row row, CellStyle style) { for (int index = 0; index < HEADERS.length; index++) { Cell cell = row.getCell(index); if (cell == null) cell = row.createCell(index); cell.setCellStyle(style); } }
    private void write(org.apache.poi.ss.usermodel.Row row, int column, Object value, CellStyle style) { Cell cell = row.createCell(column); cell.setCellValue(value == null ? "" : String.valueOf(value)); cell.setCellStyle(style); }

    /**
     * 富文本中的普通文字写入“位置”单元格；前端粘贴图片产生的 data URL 则解码并嵌入该单元格。
     * Excel 不支持在单个单元格内混排 HTML，因此图片以跟随单元格移动和缩放的绘图对象呈现。
     */
    private void writeRichTextLocation(XSSFWorkbook workbook, XSSFSheet sheet, org.apache.poi.ss.usermodel.Row row,
                                       RichTextContent richText, CellStyle style) {
        write(row, LOCATION_COLUMN, richText.plainText(), style);
        if (richText.images().isEmpty()) {
            return;
        }
        float textHeight = textHeight(richText.plainText());
        float imageHeight = Math.min(DEFAULT_IMAGE_HEIGHT_POINTS,
                Math.max(32F, (MAX_LOCATION_ROW_HEIGHT_POINTS - textHeight - 8F) / richText.images().size()));
        row.setHeightInPoints(Math.min(MAX_LOCATION_ROW_HEIGHT_POINTS,
                textHeight + imageHeight * richText.images().size() + 8F));
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        for (int index = 0; index < richText.images().size(); index++) {
            EmbeddedImage image = richText.images().get(index);
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            anchor.setCol1(LOCATION_COLUMN);
            anchor.setCol2(LOCATION_COLUMN + 1);
            anchor.setRow1(row.getRowNum());
            anchor.setRow2(row.getRowNum() + 1);
            anchor.setDy1(org.apache.poi.util.Units.toEMU((int) (textHeight + imageHeight * index)));
            anchor.setDy2(org.apache.poi.util.Units.toEMU((int) (textHeight + imageHeight * (index + 1) - 4F)));
            drawing.createPicture(anchor, workbook.addPicture(image.content(), image.pictureType()));
        }
    }

    private float textHeight(String text) {
        if (text == null || text.isBlank()) {
            return 20F;
        }
        int lines = Math.max(1, text.split("\\R", -1).length);
        return Math.min(140F, Math.max(20F, lines * 16F));
    }

    private ReviewTaskRecord requireFinishedTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId); if (task == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        if (!TaskStatus.FINISHED.name().equals(task.getStatus())) throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务尚未结束，不能导出归档意见"); return task;
    }
    private String sheetName(String role) {
        return java.util.Arrays.stream(ReviewRole.values()).filter(value -> value.name().equals(role)).findFirst()
                .map(ReviewRole::displayName).orElse("专家评审");
    }
    private String phaseName(OpinionSourceType sourceType) { return switch (sourceType) { case PCB_PROCESS_REVIEW -> "工艺评审"; case PCB_STRUCTURE_REVIEW -> "结构评审"; default -> "专家评审"; }; }
    private String severityName(OpinionSeverity severity) { return severity == null ? OpinionSeverity.GENERAL.displayName() : severity.displayName(); }
    private String handlingName(OpinionReplyRecord reply) { String action = switch (reply.getReplyType()) { case "ACCEPT" -> "接受并修改"; case "ACCEPT_NO_CHANGE" -> "接受不修改"; case "REJECT" -> "不接受"; default -> reply.getReplyType(); }; return reply.getReason() == null || reply.getReason().isBlank() ? action : action + "：" + reply.getReason(); }
    private String confirmationName(OpinionConfirmationRecord confirmation) { if (confirmation == null) return "待专家确认"; String result = Boolean.TRUE.equals(confirmation.getPassed()) ? "确认通过" : "确认不通过"; return confirmation.getComment() == null || confirmation.getComment().isBlank() ? result : result + "：" + confirmation.getComment(); }
    private String raisedByName(ReviewOpinionRecord opinion) {
        return employeeName(opinion.getRaisedByEmployeeNo(), opinion.getRaisedByName());
    }
    /**
     * 导出使用业务表中持久化的姓名，避免用户中心暂时不可用时将错误 JSON 下载成“Excel”。
     * 老数据没有姓名时才降级查询用户中心；仍无法获取时返回工号，保证工作簿始终可以生成。
     */
    private String employeeName(String employeeNo, String storedName) {
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        if (employeeNo == null || employeeNo.isBlank()) {
            return "";
        }
        try {
            UserCenterUserProfileClient.UserProfile profile = userProfileClient.getByEmployeeNo(employeeNo);
            if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
                return profile.displayName();
            }
        } catch (RuntimeException ignored) {
            // 用户中心不可用不应影响归档 Excel 导出。
        }
        return employeeNo;
    }
    private String formatDate(LocalDateTime dateTime) { return dateTime == null ? "" : DATE_FORMATTER.format(dateTime); }
    private RichTextContent richTextContent(String richText) {
        if (richText == null || richText.isBlank()) {
            return new RichTextContent("", List.of());
        }
        List<EmbeddedImage> images = new ArrayList<>();
        Matcher matcher = DATA_IMAGE_PATTERN.matcher(richText);
        while (matcher.find()) {
            image(matcher.group(1), matcher.group(2)).ifPresent(images::add);
        }
        String textWithImagePlaceholders = matcher.reset().replaceAll("[图片]");
        return new RichTextContent(richTextToPlainText(textWithImagePlaceholders), List.copyOf(images));
    }

    private java.util.Optional<EmbeddedImage> image(String imageType, String encodedContent) {
        int pictureType = switch (imageType.toLowerCase(java.util.Locale.ROOT)) {
            case "png" -> XSSFWorkbook.PICTURE_TYPE_PNG;
            case "jpeg", "jpg" -> XSSFWorkbook.PICTURE_TYPE_JPEG;
            case "gif" -> XSSFWorkbook.PICTURE_TYPE_GIF;
            case "bmp" -> XSSFWorkbook.PICTURE_TYPE_DIB;
            default -> -1;
        };
        if (pictureType < 0) {
            return java.util.Optional.empty();
        }
        try {
            byte[] content = Base64.getDecoder().decode(encodedContent);
            return content.length == 0 ? java.util.Optional.empty() : java.util.Optional.of(new EmbeddedImage(content, pictureType));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private String richTextToPlainText(String richText) { if (richText == null || richText.isBlank()) return ""; return richText.replaceAll("(?i)<br\\s*/?>", "\\n").replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\\n").replaceAll("(?s)<img[^>]*>", "[图片]").replaceAll("(?s)<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("\\n{3,}", "\\n\\n").trim(); }
    /** 导出名称仅包含项目名称、评审类型和用途，不拼接设计名称或编码参数。 */
    private String fileName(ReviewTaskRecord task) {
        String projectName = safeFilePart(task.getProjectName(), "未命名项目");
        String reviewTypeName = ReviewType.SCHEMATIC.name().equals(task.getReviewType()) ? "原理图评审" : "PCB评审";
        return projectName + reviewTypeName + "检查表.xlsx";
    }

    private String safeFilePart(String value, String defaultValue) {
        String safeValue = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return safeValue.isBlank() ? defaultValue : safeValue;
    }

    public record ExportedExcel(String fileName, byte[] content) { }
    private record ExportRow(String phase, RichTextContent location, String description, String raisedByName, String severity, String handling, String confirmation) { }
    private record RichTextContent(String plainText, List<EmbeddedImage> images) { }
    private record EmbeddedImage(byte[] content, int pictureType) { }
    private record SchematicExportRow(LocalDateTime raisedAt, String reviewObject, String description, String severity,
                                      String causeAnalysis, String handlingMeasure, LocalDateTime resolvedAt,
                                      String responsiblePerson, String status, String remark) { }
}
