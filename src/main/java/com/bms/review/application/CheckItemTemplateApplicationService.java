package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import com.bms.task.domain.ReviewType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 管理本地阶段的互检检查项模板录入；模板维护权限暂复用互检管理权限，独立版本治理将在模板管理需求明确后扩展。
 */
@Service
public class CheckItemTemplateApplicationService {
    private final CheckItemTemplateMapper templateMapper;
    private final OperationAuditMapper auditMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public CheckItemTemplateApplicationService(CheckItemTemplateMapper templateMapper, OperationAuditMapper auditMapper) {
        this.templateMapper = templateMapper;
        this.auditMapper = auditMapper;
    }

    @Transactional
    public TemplateView create(CreateTemplateCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(templateMapper.nextId());
        record.setReviewType(command.reviewType().name());
        record.setItemKey(command.itemKey());
        record.setParentItemKey(command.parentItemKey());
        record.setItemName(command.itemName());
        record.setSortNo(command.sortNo());
        record.setEnabled(true);
        record.setVersion(0L);
        templateMapper.insert(record);
        appendAudit(record.getId(), "CHECK_ITEM_TEMPLATE_CREATED", currentUser.id(), record.getItemKey());
        return TemplateView.from(record);
    }

    @Transactional
    public TemplateView update(long templateId, UpdateTemplateCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord record = templateMapper.findById(templateId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项模板不存在");
        }
        record.setItemKey(command.itemKey());
        record.setParentItemKey(command.parentItemKey());
        record.setItemName(command.itemName());
        record.setSortNo(command.sortNo());
        record.setEnabled(command.enabled());
        record.setVersion(command.version());
        if (templateMapper.update(record) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项模板已被其他操作更新，请刷新后重试");
        }
        record.setVersion(record.getVersion() + 1);
        appendAudit(record.getId(), "CHECK_ITEM_TEMPLATE_UPDATED", currentUser.id(), record.getItemKey());
        return TemplateView.from(record);
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 以一个工作簿为原子单位导入检查项模板：任一行格式或业务校验失败即整体回滚，避免部分模板生效导致活动任务检查项不完整。
     */
    @Transactional
    public ImportResult importWorkbook(byte[] workbookBytes, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "导入文件不能为空");
        }
        List<ImportRow> rows = readRows(workbookBytes);
        Set<String> duplicateGuard = new HashSet<>();
        int created = 0;
        int updated = 0;
        for (ImportRow row : rows) {
            String duplicateKey = row.reviewType().name() + "|" + row.itemKey();
            if (!duplicateGuard.add(duplicateKey)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 中存在重复检查项编码：" + duplicateKey);
            }
            CheckItemTemplateRecord existing = templateMapper.findByReviewTypeAndItemKey(row.reviewType().name(), row.itemKey());
            if (existing == null) {
                CheckItemTemplateRecord record = new CheckItemTemplateRecord();
                record.setId(templateMapper.nextId());
                record.setReviewType(row.reviewType().name());
                record.setItemKey(row.itemKey());
                record.setParentItemKey(row.parentItemKey());
                record.setItemName(row.itemName());
                record.setSortNo(row.sortNo());
                record.setEnabled(row.enabled());
                record.setVersion(0L);
                templateMapper.insert(record);
                appendAudit(record.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_CREATED", currentUser.id(), record.getItemKey());
                created++;
            } else {
                existing.setParentItemKey(row.parentItemKey());
                existing.setItemName(row.itemName());
                existing.setSortNo(row.sortNo());
                existing.setEnabled(row.enabled());
                if (templateMapper.update(existing) != 1) {
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项模板导入时发生并发更新：" + row.itemKey());
                }
                appendAudit(existing.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_UPDATED", currentUser.id(), existing.getItemKey());
                updated++;
            }
        }
        return new ImportResult(rows.size(), created, updated);
    }

    private List<ImportRow> readRows(byte[] workbookBytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 不包含工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> headers = readHeaders(sheet);
            DataFormatter formatter = new DataFormatter();
            List<ImportRow> rows = new java.util.ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                int displayRow = rowIndex + 1;
                String reviewTypeValue = cell(row, headers, "评审类型", formatter);
                String itemKey = required(cell(row, headers, "检查项编码", formatter), displayRow, "检查项编码");
                String itemName = required(cell(row, headers, "检查项名称", formatter), displayRow, "检查项名称");
                rows.add(new ImportRow(parseReviewType(reviewTypeValue, displayRow), itemKey,
                        blankToNull(cell(row, headers, "父级检查项编码", formatter)), itemName,
                        parseSortNo(cell(row, headers, "排序号", formatter), displayRow),
                        parseEnabled(cell(row, headers, "是否启用", formatter), displayRow)));
            }
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 至少需要一条检查项模板数据");
            }
            return rows;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无法读取检查项模板 Excel：" + exception.getMessage());
        }
    }

    private Map<String, Integer> readHeaders(Sheet sheet) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 缺少表头");
        }
        DataFormatter formatter = new DataFormatter();
        Map<String, Integer> headers = new HashMap<>();
        for (int cellIndex = header.getFirstCellNum(); cellIndex < header.getLastCellNum(); cellIndex++) {
            headers.put(formatter.formatCellValue(header.getCell(cellIndex)).trim(), cellIndex);
        }
        for (String requiredHeader : List.of("评审类型", "检查项编码", "父级检查项编码", "检查项名称", "排序号", "是否启用")) {
            if (!headers.containsKey(requiredHeader)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 缺少必填表头：" + requiredHeader);
            }
        }
        return headers;
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
            if (!formatter.formatCellValue(row.getCell(cellIndex)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cell(Row row, Map<String, Integer> headers, String header, DataFormatter formatter) {
        return formatter.formatCellValue(row.getCell(headers.get(header))).trim();
    }

    private String required(String value, int row, String column) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + row + " 行“" + column + "”不能为空");
        }
        return value;
    }

    private ReviewType parseReviewType(String value, int row) {
        try {
            return ReviewType.valueOf(required(value, row, "评审类型").toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + row + " 行评审类型仅支持 PCB 或 SCHEMATIC");
        }
    }

    private int parseSortNo(String value, int row) {
        try {
            int sortNo = Integer.parseInt(required(value, row, "排序号"));
            if (sortNo < 0) {
                throw new NumberFormatException();
            }
            return sortNo;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + row + " 行排序号必须是大于等于 0 的整数");
        }
    }

    private boolean parseEnabled(String value, int row) {
        return switch (required(value, row, "是否启用").toLowerCase()) {
            case "是", "true", "1", "启用" -> true;
            case "否", "false", "0", "停用" -> false;
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + row + " 行是否启用仅支持 是/否、true/false 或 1/0");
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_MUTUAL_CHECK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无检查项模板管理权限");
        }
    }

    private void appendAudit(long templateId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("CHECK_ITEM_TEMPLATE", templateId, action, operatorId, detail));
    }

    public record CreateTemplateCommand(ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo) {
    }

    public record UpdateTemplateCommand(String itemKey, String parentItemKey, String itemName, int sortNo, boolean enabled, long version) {
    }

    public record ImportResult(int totalRows, int createdCount, int updatedCount) {
    }

    public record TemplateView(Long id, ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo) {
        static TemplateView from(CheckItemTemplateRecord record) {
            return new TemplateView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getItemKey(),
                    record.getParentItemKey(), record.getItemName(), record.getSortNo());
        }
    }

    private record ImportRow(ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo, boolean enabled) {
    }
}
