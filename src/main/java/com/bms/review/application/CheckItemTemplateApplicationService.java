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
import java.util.Comparator;
import java.util.ArrayList;
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
    public TemplateCategoryView create(CreateCategoryCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        String categoryKey = generatedInternalKey(command.reviewType(), "CATEGORY", command.categoryName());
        CheckItemTemplateRecord category = newTemplate(command.reviewType(), categoryKey, null, command.categoryName(), nextCategorySort(command.reviewType()), true);
        templateMapper.insert(category);
        List<TemplateView> items = new ArrayList<>();
        List<CreateItemCommand> requestedItems = command.items() == null ? List.of() : command.items();
        for (int index = 0; index < requestedItems.size(); index++) {
            CreateItemCommand item = requestedItems.get(index);
            CheckItemTemplateRecord child = newTemplate(command.reviewType(), generatedInternalKey(command.reviewType(), "ITEM", item.itemName()), category.getItemKey(), item.itemName(), index, true);
            templateMapper.insert(child);
            items.add(TemplateView.from(child));
        }
        appendAudit(category.getId(), "CHECK_ITEM_TEMPLATE_CREATED", currentUser.id(), category.getItemKey());
        return new TemplateCategoryView(TemplateView.from(category), List.copyOf(items));
    }

    @Transactional
    public TemplateCategoryView update(UpdateCategoryCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord category = templateMapper.findById(command.categoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项模板不存在");
        }
        if (category.getParentItemKey() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请使用检查项大类 ID 调整类别及其子检查项");
        }
        String categoryKey = category.getItemKey();
        category.setItemName(command.categoryName());
        if (templateMapper.update(category) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项模板已被其他操作更新，请刷新后重试");
        }
        List<TemplateView> items = new ArrayList<>();
        for (int index = 0; index < command.items().size(); index++) {
            UpdateItemCommand item = command.items().get(index);
            CheckItemTemplateRecord child = item.itemId() == null
                    ? newTemplate(ReviewType.valueOf(category.getReviewType()), generatedInternalKey(ReviewType.valueOf(category.getReviewType()), "ITEM", item.itemName()), categoryKey, item.itemName(), index, true)
                    : templateMapper.findById(item.itemId());
            if (child == null || child.getParentItemKey() == null || !child.getParentItemKey().equals(categoryKey)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不属于当前类别");
            }
            child.setParentItemKey(categoryKey); child.setItemName(item.itemName()); child.setSortNo(index);
            if (item.itemId() == null) { templateMapper.insert(child); } else if (templateMapper.update(child) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项已被其他操作更新，请刷新后重试");
            items.add(TemplateView.from(child));
        }
        appendAudit(category.getId(), "CHECK_ITEM_TEMPLATE_UPDATED", currentUser.id(), category.getItemKey());
        return new TemplateCategoryView(TemplateView.from(category), List.copyOf(items));
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 仅修改一个检查项小类的展示名称。前端只需提供小类记录 ID 和新名称，不需要回传大类、排序、内部编码或版本字段。
     */
    @Transactional
    public TemplateView updateItemName(long checkItemId, String itemName, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord item = templateMapper.findById(checkItemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        if (item.getParentItemKey() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只能编辑检查项小类名称");
        }
        item.setItemName(itemName.trim());
        if (templateMapper.update(item) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项已被其他操作更新，请刷新后重试");
        }
        appendAudit(item.getId(), "CHECK_ITEM_TEMPLATE_ITEM_RENAMED", currentUser.id(), item.getItemKey());
        return TemplateView.from(item);
    }

    /**
     * @author 王涛
     * @date 2026-09-20
     * @description 按评审类型读取互检模板的类别和子检查项树，供管理端展示、编辑和导入后校验使用；包含已停用记录以便管理人员追溯。
     */
    public List<TemplateListCategoryView> list(ReviewType reviewType, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType == null ? null : reviewType.name());
        Map<String, List<TemplateListItemView>> childrenByParentKey = new HashMap<>();
        records.stream().filter(record -> record.getParentItemKey() != null)
                .forEach(record -> childrenByParentKey.computeIfAbsent(record.getReviewType() + "|" + record.getParentItemKey(), ignored -> new java.util.ArrayList<>()).add(TemplateListItemView.from(record)));
        childrenByParentKey.values().forEach(items -> items.sort(Comparator.comparing(TemplateListItemView::sortNo).thenComparing(TemplateListItemView::id)));
        return records.stream().filter(record -> record.getParentItemKey() == null)
                .sorted(Comparator.comparing(CheckItemTemplateRecord::getSortNo).thenComparing(CheckItemTemplateRecord::getId))
                .map(category -> new TemplateListCategoryView(TemplateListItemView.from(category),
                        List.copyOf(childrenByParentKey.getOrDefault(category.getReviewType() + "|" + category.getItemKey(), List.of()))))
                .toList();
    }

    /**
     * @author 王涛
     * @date 2026-09-20
     * @description 按前端明确指定的大类或小类逻辑停用模板。大类删除会级联停用本评审类型下的所有子项；类别与实际记录层级不一致时拒绝执行，避免误删。
     */
    @Transactional
    public void disable(long id, DeleteTargetCategory category, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord template = templateMapper.findById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        boolean isCategory = template.getParentItemKey() == null;
        if (category == DeleteTargetCategory.CATEGORY && !isCategory) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除类别时只能传检查项大类 ID");
        }
        if (category == DeleteTargetCategory.ITEM && isCategory) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除检查项时只能传检查项小类 ID");
        }
        if (templateMapper.disableById(id) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        if (isCategory) {
            templateMapper.disableChildrenByParentItemKey(template.getReviewType(), template.getItemKey());
        }
        appendAudit(template.getId(), "CHECK_ITEM_TEMPLATE_DISABLED", currentUser.id(), template.getItemKey());
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
            Map<String, Boolean> importedCategories = new HashMap<>();
            String previousCategoryName = null;
            int generatedSortNo = 0;
            for (int rowIndex = headers.remove("__HEADER_ROW__") + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                int displayRow = rowIndex + 1;
                String reviewTypeValue = headers.containsKey("评审类型") ? cell(row, headers, "评审类型", formatter) : "PCB";
                ReviewType reviewType = parseReviewType(reviewTypeValue, displayRow);
                if (headers.containsKey("类别")) {
                    String categoryCell = cell(row, headers, "类别", formatter);
                    if (!categoryCell.isBlank()) {
                        previousCategoryName = categoryCell;
                    }
                    String itemCell = cell(row, headers, "检查项", formatter);
                    if (itemCell.isBlank()) {
                        continue;
                    }
                    if (previousCategoryName == null || previousCategoryName.isBlank()) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + displayRow + " 行检查项缺少类别；合并类别单元格仅可继承上一条非空类别");
                    }
                    String categoryKey = generatedKey(reviewType, "CATEGORY", previousCategoryName);
                    int sortNo = generatedSortNo++;
                    if (importedCategories.putIfAbsent(reviewType.name() + "|" + categoryKey, true) == null) {
                        rows.add(new ImportRow(reviewType, categoryKey, null, previousCategoryName, sortNo, true));
                    }
                    for (String itemName : splitEmbeddedItems(itemCell)) {
                        rows.add(new ImportRow(reviewType, generatedKey(reviewType, categoryKey, itemName), categoryKey, itemName, generatedSortNo++, true));
                    }
                } else if (headers.containsKey("类别编码")) {
                    String categoryKey = required(cell(row, headers, "类别编码", formatter), displayRow, "类别编码");
                    String categoryName = required(cell(row, headers, "类别名称", formatter), displayRow, "类别名称");
                    int sortNo = parseSortNo(cell(row, headers, "排序号", formatter), displayRow);
                    boolean enabled = headers.containsKey("是否启用") ? parseEnabled(cell(row, headers, "是否启用", formatter), displayRow) : true;
                    if (importedCategories.putIfAbsent(reviewType.name() + "|" + categoryKey, true) == null) {
                        rows.add(new ImportRow(reviewType, categoryKey, null, categoryName, sortNo, enabled));
                    }
                    rows.add(new ImportRow(reviewType, required(cell(row, headers, "检查项编码", formatter), displayRow, "检查项编码"), categoryKey,
                            required(cell(row, headers, "检查项名称", formatter), displayRow, "检查项名称"), sortNo, enabled));
                } else {
                    String itemKey = required(cell(row, headers, "检查项编码", formatter), displayRow, "检查项编码");
                    String itemName = required(cell(row, headers, "检查项名称", formatter), displayRow, "检查项名称");
                    rows.add(new ImportRow(reviewType, itemKey, blankToNull(cell(row, headers, "父级检查项编码", formatter)), itemName,
                            parseSortNo(cell(row, headers, "排序号", formatter), displayRow), parseEnabled(cell(row, headers, "是否启用", formatter), displayRow)));
                }
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
        DataFormatter formatter = new DataFormatter();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 20); rowIndex++) {
            Row header = sheet.getRow(rowIndex);
            if (header == null) { continue; }
            Map<String, Integer> headers = new HashMap<>();
            for (int cellIndex = header.getFirstCellNum(); cellIndex < header.getLastCellNum(); cellIndex++) {
                headers.put(formatter.formatCellValue(header.getCell(cellIndex)).trim(), cellIndex);
            }
            if (headers.containsKey("类别") && headers.containsKey("检查项")) {
                headers.put("__HEADER_ROW__", rowIndex);
                return headers;
            }
            List<String> requiredHeaders = headers.containsKey("类别编码")
                    ? List.of("类别编码", "类别名称", "检查项编码", "检查项名称", "排序号")
                    : List.of("评审类型", "检查项编码", "父级检查项编码", "检查项名称", "排序号", "是否启用");
            if (requiredHeaders.stream().allMatch(headers::containsKey)) {
                headers.put("__HEADER_ROW__", rowIndex);
                return headers;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未找到检查项模板表头。支持“类别、检查项”（正式 PCB 互评表）或旧版编码表头。");
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

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 仅按单元格首行或换行后的“1)”“2)”编号拆分检查项，兼容中文右括号及 Excel 转义出的“\\)”。不把 1.5mm、12.5mm 等正文数值识别为编号。
     */
    private List<String> splitEmbeddedItems(String itemCell) {
        String normalized = itemCell == null ? "" : itemCell.replace(' ', ' ').trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*\\d{1,3}\\s*(?:\\\\?\\)|）)")
                .matcher(normalized);
        List<Integer> starts = new java.util.ArrayList<>();
        List<Integer> contents = new java.util.ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            contents.add(matcher.end());
        }
        if (starts.isEmpty()) {
            return List.of(normalized);
        }
        List<String> items = new java.util.ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = contents.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : normalized.length();
            String item = normalized.substring(start, end).trim();
            if (!item.isBlank()) {
                items.add(item);
            }
        }
        return items.isEmpty() ? List.of(normalized) : List.copyOf(items);
    }

    private String generatedKey(ReviewType reviewType, String prefix, String value) {
        return reviewType.name() + "-" + prefix + "-" + Integer.toUnsignedString(value.trim().replaceAll("\\s+", " ").hashCode(), 16).toUpperCase();
    }

    private String generatedInternalKey(ReviewType reviewType, String prefix, String value) {
        return generatedKey(reviewType, prefix, value) + "-" + templateMapper.nextId();
    }

    private int nextCategorySort(ReviewType reviewType) {
        return templateMapper.findAll(reviewType.name()).stream()
                .filter(record -> record.getParentItemKey() == null)
                .map(CheckItemTemplateRecord::getSortNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .map(value -> value + 1)
                .orElse(0);
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_MUTUAL_CHECK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无检查项模板管理权限");
        }
    }

    private void appendAudit(long templateId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("CHECK_ITEM_TEMPLATE", templateId, action, operatorId, detail));
    }

    private CheckItemTemplateRecord newTemplate(ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo, boolean enabled) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(templateMapper.nextId()); record.setReviewType(reviewType.name()); record.setItemKey(itemKey); record.setParentItemKey(parentItemKey);
        record.setItemName(itemName); record.setSortNo(sortNo); record.setEnabled(enabled); record.setVersion(0L); return record;
    }
    private void requireUniqueItemKey(ReviewType reviewType, String itemKey) {
        if (templateMapper.findByReviewTypeAndItemKey(reviewType.name(), itemKey) != null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检查项编码已存在：" + itemKey);
    }

    public record CreateCategoryCommand(ReviewType reviewType, String categoryName, List<CreateItemCommand> items) { }
    public record CreateItemCommand(String itemName) { }
    public record UpdateCategoryCommand(long categoryId, String categoryName, List<UpdateItemCommand> items) { }
    public record UpdateItemCommand(Long itemId, String itemName) { }
    public enum DeleteTargetCategory {
        CATEGORY,
        ITEM
    }

    public record ImportResult(int totalRows, int createdCount, int updatedCount) {
    }

    public record TemplateView(Long id, ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo,
                               boolean enabled, long version) {
        static TemplateView from(CheckItemTemplateRecord record) {
            return new TemplateView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getItemKey(),
                    record.getParentItemKey(), record.getItemName(), record.getSortNo(), Boolean.TRUE.equals(record.getEnabled()), record.getVersion());
        }
    }
    public record TemplateCategoryView(TemplateView category, List<TemplateView> items) { }
    /**
     * 模板列表只负责展示类别与子项层级，不暴露内部编码、评审类型、启用状态或版本控制字段。
     */
    public record TemplateListCategoryView(TemplateListItemView category, List<TemplateListItemView> items) { }
    public record TemplateListItemView(Long id, String itemName, int sortNo) {
        static TemplateListItemView from(CheckItemTemplateRecord record) {
            return new TemplateListItemView(record.getId(), record.getItemName(), record.getSortNo());
        }
    }

    private record ImportRow(ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo, boolean enabled) {
    }
}
