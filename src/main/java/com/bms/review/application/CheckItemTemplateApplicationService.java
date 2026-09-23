package com.bms.review.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 管理互检检查项模板。类别与子项仅以记录 ID 和 parentId 建立父子关系，不维护业务编码。
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
        requireName(command.categoryName(), "检查项大类名称");
        CheckItemTemplateRecord category = newTemplate(command.reviewType(), null, command.categoryName(), nextCategorySort(command.reviewType()), true);
        templateMapper.insert(category);
        List<TemplateView> items = new ArrayList<>();
        List<CreateItemCommand> requestedItems = command.items() == null ? List.of() : command.items();
        for (int index = 0; index < requestedItems.size(); index++) {
            CreateItemCommand item = requestedItems.get(index);
            requireName(item.itemName(), "检查项名称");
            CheckItemTemplateRecord child = newTemplate(command.reviewType(), category.getId(), item.itemName(), index + 1, true);
            templateMapper.insert(child);
            items.add(TemplateView.from(child));
        }
        appendAudit(category.getId(), "CHECK_ITEM_TEMPLATE_CREATED", currentUser.id(), category.getItemName());
        return new TemplateCategoryView(TemplateView.from(category), List.copyOf(items));
    }

    @Transactional
    public TemplateUpdateView update(UpdateTemplateCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord target = templateMapper.findById(command.itemId());
        if (target == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        requireName(command.itemName(), "检查项名称");
        if (target.getParentId() != null) {
            if (command.items() != null && !command.items().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "修改检查项小类时不能携带子项列表");
            }
            target.setItemName(command.itemName().trim());
            templateMapper.update(target);
            appendAudit(target.getId(), "CHECK_ITEM_TEMPLATE_ITEM_RENAMED", currentUser.id(), target.getItemName());
            return new TemplateUpdateView(TemplateView.from(target), List.of());
        }

        target.setItemName(command.itemName().trim());
        templateMapper.update(target);

        List<TemplateView> items = new ArrayList<>();
        List<UpdateItemCommand> requestedItems = command.items() == null ? List.of() : command.items();
        for (int index = 0; index < requestedItems.size(); index++) {
            UpdateItemCommand item = requestedItems.get(index);
            requireName(item.itemName(), "检查项名称");
            CheckItemTemplateRecord child;
            if (item.itemId() == null) {
                child = newTemplate(ReviewType.valueOf(target.getReviewType()), target.getId(), item.itemName(), index + 1, true);
                templateMapper.insert(child);
            } else {
                child = templateMapper.findById(item.itemId());
                if (child == null || !Objects.equals(child.getParentId(), target.getId())) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不属于当前类别");
                }
                child.setItemName(item.itemName().trim());
                child.setSortNo(index + 1);
                templateMapper.update(child);
            }
            items.add(TemplateView.from(child));
        }
        appendAudit(target.getId(), "CHECK_ITEM_TEMPLATE_UPDATED", currentUser.id(), target.getItemName());
        return new TemplateUpdateView(TemplateView.from(target), List.copyOf(items));
    }

    /** 按评审类型读取互检模板树；层级关系只由 parentId 表达。 */
    public List<TemplateListCategoryView> list(ReviewType reviewType, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType == null ? null : reviewType.name());
        Map<Long, List<TemplateListItemView>> childrenByParentId = new HashMap<>();
        records.stream().filter(record -> record.getParentId() != null)
                .forEach(record -> childrenByParentId.computeIfAbsent(record.getParentId(), ignored -> new ArrayList<>()).add(TemplateListItemView.from(record)));
        childrenByParentId.values().forEach(items -> items.sort(Comparator.comparing(TemplateListItemView::sortNo).thenComparing(TemplateListItemView::id)));
        return records.stream().filter(record -> record.getParentId() == null)
                .sorted(Comparator.comparing(CheckItemTemplateRecord::getSortNo).thenComparing(CheckItemTemplateRecord::getId))
                .map(category -> new TemplateListCategoryView(TemplateListItemView.from(category),
                        List.copyOf(childrenByParentId.getOrDefault(category.getId(), List.of()))))
                .toList();
    }

    @Transactional
    public void disable(long id, DeleteTargetCategory category, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord template = templateMapper.findById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        boolean isCategory = template.getParentId() == null;
        if (category == DeleteTargetCategory.CATEGORY && !isCategory) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除类别时只能传检查项大类 ID");
        }
        if (category == DeleteTargetCategory.ITEM && isCategory) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除检查项时只能传检查项小类 ID");
        }
        templateMapper.disableById(id);
        if (isCategory) {
            templateMapper.disableChildrenByParentId(id);
        }
        appendAudit(template.getId(), "CHECK_ITEM_TEMPLATE_DISABLED", currentUser.id(), template.getItemName());
    }

    /**
     * 导入正式 Excel 的“类别、检查项”列。一个检查项单元格中可使用换行开头的 1)、2) 编号表达多个子项。
     * 文件中不再使用或保存检查项编码。
     */
    @Transactional
    public ImportResult importWorkbook(byte[] workbookBytes, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        List<ImportCategory> categories = readCategories(workbookBytes);
        int created = 0;
        int updated = 0;
        for (ImportCategory imported : categories) {
            CheckItemTemplateRecord category = templateMapper.findCategoryByReviewTypeAndName(imported.reviewType().name(), imported.categoryName());
            if (category == null) {
                category = newTemplate(imported.reviewType(), null, imported.categoryName(), imported.sortNo(), true);
                templateMapper.insert(category);
                appendAudit(category.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_CREATED", currentUser.id(), category.getItemName());
                created++;
            } else {
                category.setSortNo(imported.sortNo());
                category.setEnabled(true);
                templateMapper.update(category);
                appendAudit(category.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_UPDATED", currentUser.id(), category.getItemName());
                updated++;
            }
            for (int index = 0; index < imported.itemNames().size(); index++) {
                String itemName = imported.itemNames().get(index);
                CheckItemTemplateRecord item = templateMapper.findChildByParentIdAndName(category.getId(), itemName);
                if (item == null) {
                    item = newTemplate(imported.reviewType(), category.getId(), itemName, index + 1, true);
                    templateMapper.insert(item);
                    appendAudit(item.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_CREATED", currentUser.id(), item.getItemName());
                    created++;
                } else {
                    item.setSortNo(index + 1);
                    item.setEnabled(true);
                    templateMapper.update(item);
                    appendAudit(item.getId(), "CHECK_ITEM_TEMPLATE_IMPORTED_UPDATED", currentUser.id(), item.getItemName());
                    updated++;
                }
            }
        }
        int totalRows = categories.stream().mapToInt(category -> 1 + category.itemNames().size()).sum();
        return new ImportResult(totalRows, created, updated);
    }

    private List<ImportCategory> readCategories(byte[] workbookBytes) {
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "导入文件不能为空");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 不包含工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> headers = readHeaders(sheet);
            DataFormatter formatter = new DataFormatter();
            Map<String, ImportCategoryBuilder> categories = new LinkedHashMap<>();
            Map<ReviewType, String> previousCategoryNames = new HashMap<>();
            Map<ReviewType, Integer> categorySorts = new HashMap<>();
            int startRow = headers.remove("__HEADER_ROW__") + 1;
            for (int rowIndex = startRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                int displayRow = rowIndex + 1;
                ReviewType reviewType = parseReviewType(headers.containsKey("评审类型") ? cell(row, headers, "评审类型", formatter) : "PCB", displayRow);
                String categoryName = cell(row, headers, "类别", formatter);
                if (!categoryName.isBlank()) {
                    previousCategoryNames.put(reviewType, categoryName);
                } else {
                    categoryName = previousCategoryNames.get(reviewType);
                }
                if (categoryName == null || categoryName.isBlank()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + displayRow + " 行检查项缺少类别；合并类别单元格仅可继承上一条非空类别");
                }
                String normalizedCategoryName = categoryName.trim();
                String key = reviewType.name() + "|" + normalizedCategoryName;
                ImportCategoryBuilder category = categories.computeIfAbsent(key, ignored -> new ImportCategoryBuilder(reviewType, normalizedCategoryName,
                        categorySorts.merge(reviewType, 1, Integer::sum)));
                String items = cell(row, headers, "检查项", formatter);
                for (String itemName : splitEmbeddedItems(items)) {
                    if (!category.itemNames.add(itemName)) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + displayRow + " 行存在重复检查项：" + itemName);
                    }
                }
            }
            if (categories.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 至少需要一条检查项模板数据");
            }
            return categories.values().stream().map(ImportCategoryBuilder::toValue).toList();
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
            if (header == null) continue;
            Map<String, Integer> headers = new HashMap<>();
            for (int cellIndex = header.getFirstCellNum(); cellIndex < header.getLastCellNum(); cellIndex++) {
                headers.put(formatter.formatCellValue(header.getCell(cellIndex)).trim(), cellIndex);
            }
            if (headers.containsKey("类别") && headers.containsKey("检查项")) {
                headers.put("__HEADER_ROW__", rowIndex);
                return headers;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未找到检查项模板表头；仅支持“类别、检查项”列，可选“评审类型”列");
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
            if (!formatter.formatCellValue(row.getCell(cellIndex)).isBlank()) return false;
        }
        return true;
    }

    private String cell(Row row, Map<String, Integer> headers, String header, DataFormatter formatter) {
        return formatter.formatCellValue(row.getCell(headers.get(header))).trim();
    }

    private ReviewType parseReviewType(String value, int row) {
        try {
            return ReviewType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + row + " 行评审类型仅支持 PCB 或 SCHEMATIC");
        }
    }

    /** 仅按行首编号拆分，避免把 1.5mm、12.5mm 等正文数值当作检查项编号。 */
    private List<String> splitEmbeddedItems(String itemCell) {
        String normalized = itemCell == null ? "" : itemCell.replace(' ', ' ').trim();
        if (normalized.isBlank()) return List.of();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*\\d{1,3}\\s*(?:\\\\?\\)|）)").matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        List<Integer> contents = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            contents.add(matcher.end());
        }
        if (starts.isEmpty()) return List.of(normalized);
        List<String> items = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String item = normalized.substring(contents.get(index), index + 1 < starts.size() ? starts.get(index + 1) : normalized.length()).trim();
            if (!item.isBlank()) items.add(item);
        }
        return List.copyOf(items);
    }

    private int nextCategorySort(ReviewType reviewType) {
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType.name());
        return (records == null ? List.<CheckItemTemplateRecord>of() : records).stream().filter(record -> record.getParentId() == null)
                .map(CheckItemTemplateRecord::getSortNo).filter(Objects::nonNull).max(Integer::compareTo).map(value -> value + 1).orElse(1);
    }

    private CheckItemTemplateRecord newTemplate(ReviewType reviewType, Long parentId, String itemName, int sortNo, boolean enabled) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(templateMapper.nextId());
        record.setReviewType(reviewType.name());
        record.setParentId(parentId);
        record.setItemName(itemName.trim());
        record.setSortNo(sortNo);
        record.setEnabled(enabled);
        record.setVersion(0L);
        return record;
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_MUTUAL_CHECK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无检查项模板管理权限");
        }
    }

    private void requireName(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, fieldName + "不能为空");
    }

    private void appendAudit(long templateId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("CHECK_ITEM_TEMPLATE", templateId, action, operatorId, detail));
    }

    public record CreateCategoryCommand(ReviewType reviewType, String categoryName, List<CreateItemCommand> items) { }
    public record CreateItemCommand(String itemName) { }
    public record UpdateTemplateCommand(long itemId, String itemName, List<UpdateItemCommand> items) { }
    public record UpdateItemCommand(Long itemId, String itemName) { }
    public enum DeleteTargetCategory { CATEGORY, ITEM }
    public record ImportResult(int totalRows, int createdCount, int updatedCount) { }

    public record TemplateView(Long id, ReviewType reviewType, Long parentId, String itemName, int sortNo) {
        static TemplateView from(CheckItemTemplateRecord record) {
            return new TemplateView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getParentId(), record.getItemName(), record.getSortNo());
        }
    }
    public record TemplateCategoryView(TemplateView category, List<TemplateView> items) { }
    /** 统一修改接口的返回模型；小类修改时 items 为空，大类修改时返回本次处理的子项。 */
    public record TemplateUpdateView(TemplateView item, List<TemplateView> items) { }
    public record TemplateListCategoryView(TemplateListItemView category, List<TemplateListItemView> items) { }
    public record TemplateListItemView(Long id, String itemName, int sortNo) {
        static TemplateListItemView from(CheckItemTemplateRecord record) {
            return new TemplateListItemView(record.getId(), record.getItemName(), record.getSortNo());
        }
    }

    private record ImportCategory(ReviewType reviewType, String categoryName, List<String> itemNames, int sortNo) { }
    private static final class ImportCategoryBuilder {
        private final ReviewType reviewType;
        private final String categoryName;
        private final int sortNo;
        private final Set<String> itemNames = new java.util.LinkedHashSet<>();
        private ImportCategoryBuilder(ReviewType reviewType, String categoryName, int sortNo) {
            this.reviewType = reviewType;
            this.categoryName = categoryName;
            this.sortNo = sortNo;
        }
        private ImportCategory toValue() { return new ImportCategory(reviewType, categoryName, List.copyOf(itemNames), sortNo); }
    }
}
