package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import com.bms.task.domain.ReviewType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    /** 行首检查项序号；小数值（如 1.5mm）不属于序号，不能删除。 */
    private static final java.util.regex.Pattern ITEM_SERIAL_PATTERN = java.util.regex.Pattern.compile(
            "(?m)^\\s*(?:(?:\\d{1,3}(?:\\.\\d{1,3})+(?!\\s*(?:mm|mil)\\b))|(?:\\d{1,3}\\s*(?:[.．、:：](?!\\d)|\\\\?\\)|）)))\\s*");
    private final CheckItemTemplateMapper templateMapper;

    public CheckItemTemplateApplicationService(CheckItemTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    /** 导入前供页面判断是否需要提示用户确认替换当前模板。 */
    public TemplateExistenceView checkExistence(ReviewType reviewType, String operatorEmployeeNo, String operatorName) {
        if (reviewType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "查询互检单模板时必须选择任务类型");
        }
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType.name());
        int categoryCount = (int) records.stream().filter(record -> record.getParentId() == null).count();
        int itemCount = records.size() - categoryCount;
        return new TemplateExistenceView(reviewType, !records.isEmpty(), categoryCount, itemCount);
    }

    /** 兼容应用层已有调用：未显式确认时绝不替换已有模板。 */
    public ImportResult importWorkbook(byte[] workbookBytes, ReviewType reviewType, String operatorEmployeeNo, String operatorName) {
        return importWorkbook(workbookBytes, reviewType, false, operatorEmployeeNo, operatorName);
    }

    /**
     * 按评审类型导入互检单 Excel：PCB 读取“类别、检查项”，原理图读取“检查项类别、检查内容”。
     * 检查项可按换行编号拆分；导入时会跳过“...”这类占位内容，并移除展示用的行首序号。
     * PCB 检查项同时会保留 .xlsx 单元格内嵌的示例图片。
     */
    @Transactional
    public ImportResult importWorkbook(byte[] workbookBytes, ReviewType reviewType, boolean confirmed, String operatorEmployeeNo, String operatorName) {
        if (reviewType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "导入互检单模板时必须选择任务类型");
        }
        List<CheckItemTemplateRecord> existingTemplates = templateMapper.findAll(reviewType.name());
        // 先由存在性接口提示前端；这里仍保留校验，避免并发或绕过页面确认时误覆盖模板。
        if (!existingTemplates.isEmpty() && !confirmed) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST,
                    (reviewType == ReviewType.PCB ? "PCB" : "原理图") + "互检单模板已存在，请确认后重新导入");
        }
        // 用户确认后，逻辑停用旧模板；已创建任务使用的是任务检查项快照，不会被影响。
        int replacedCount = existingTemplates.isEmpty() ? 0 : templateMapper.disableEnabledByReviewType(reviewType.name());
        List<ImportCategory> categories = readCategories(workbookBytes, reviewType);
        int created = 0;
        for (ImportCategory imported : categories) {
            // 导入校验已保证当前类型没有模板，以下只创建一套完整的类别—子项树。
            CheckItemTemplateRecord category = newTemplate(imported.reviewType(), null, imported.categoryName(), imported.sortNo(), true);
            templateMapper.insert(category);
            created++;
            for (int index = 0; index < imported.items().size(); index++) {
                ImportItem importedItem = imported.items().get(index);
                CheckItemTemplateRecord item = newTemplate(imported.reviewType(), category.getId(), importedItem.itemName(), importedItem.itemRichText(), index + 1, true);
                templateMapper.insert(item);
                created++;
            }
        }
        int totalRows = categories.stream().mapToInt(category -> 1 + category.items().size()).sum();
        return new ImportResult(totalRows, created, replacedCount);
    }

    /**
     * 每种评审类型仅维护一套互检单模板；层级关系只由 parentId 表达。
     */
    public TemplateListView get(ReviewType reviewType, String operatorEmployeeNo, String operatorName) {
        if (reviewType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "查询互检单模板时必须选择任务类型");
        }
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType.name());
        Map<Long, List<TemplateListItemView>> childrenByParentId = new HashMap<>();
        records.stream().filter(record -> record.getParentId() != null)
                .forEach(record -> childrenByParentId.computeIfAbsent(record.getParentId(), ignored -> new ArrayList<>()).add(TemplateListItemView.from(record)));
        childrenByParentId.values().forEach(items -> items.sort(Comparator.comparing(TemplateListItemView::sortNo).thenComparing(TemplateListItemView::id)));
        List<TemplateListCategoryView> categories = records.stream().filter(record -> record.getParentId() == null)
                .sorted(Comparator.comparing(CheckItemTemplateRecord::getSortNo).thenComparing(CheckItemTemplateRecord::getId))
                .map(category -> new TemplateListCategoryView(TemplateListItemView.from(category),
                        List.copyOf(childrenByParentId.getOrDefault(category.getId(), List.of()))))
                .toList();
        return new TemplateListView(reviewType, categories);
    }

    @Transactional
    public TemplateCategoryView create(CreateCategoryCommand command, String operatorEmployeeNo, String operatorName) {
        requireName(command.categoryName(), "检查项大类名称");
        List<CreateItemCommand> requestedItems = command.items() == null ? List.of() : command.items();
        requireDistinctChildItemNames(requestedItems.stream().map(CreateItemCommand::itemName).toList());
        if (templateMapper.findCategoryByReviewTypeAndName(command.reviewType().name(), command.categoryName().trim()) != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "同一评审类型下不能存在同名检查项大类");
        }
        CheckItemTemplateRecord category = newTemplate(command.reviewType(), null, command.categoryName(), nextCategorySort(command.reviewType()), true);
        templateMapper.insert(category);
        List<TemplateView> items = new ArrayList<>();
        for (int index = 0; index < requestedItems.size(); index++) {
            CreateItemCommand item = requestedItems.get(index);
            requireName(item.itemName(), "检查项名称");
            CheckItemTemplateRecord child = newTemplate(command.reviewType(), category.getId(), item.itemName(), index + 1, true);
            templateMapper.insert(child);
            items.add(TemplateView.from(child));
        }
        return new TemplateCategoryView(TemplateView.from(category), List.copyOf(items));
    }

    /** 将一个子检查项追加到指定大类末尾，已有任务使用的是快照，不受模板变更影响。 */
    @Transactional
    public TemplateView createItem(long categoryId, String itemName) {
        CheckItemTemplateRecord category = templateMapper.findById(categoryId);
        if (category == null || category.getParentId() != null || !Boolean.TRUE.equals(category.getEnabled())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项大类不存在或已停用");
        }
        requireName(itemName, "检查项名称");
        if (templateMapper.findChildByParentIdAndName(categoryId, itemName.trim()) != null) {
            throw duplicateChildItemName();
        }
        int sortNo = templateMapper.findAll(category.getReviewType()).stream()
                .filter(record -> Objects.equals(record.getParentId(), categoryId))
                .map(CheckItemTemplateRecord::getSortNo).filter(Objects::nonNull)
                .max(Integer::compareTo).map(value -> value + 1).orElse(1);
        CheckItemTemplateRecord item = newTemplate(ReviewType.valueOf(category.getReviewType()), categoryId, itemName, sortNo, true);
        templateMapper.insert(item);
        return TemplateView.from(item);
    }

    @Transactional
    public TemplateUpdateView update(UpdateTemplateCommand command, String operatorEmployeeNo, String operatorName) {
        CheckItemTemplateRecord target = templateMapper.findById(command.itemId());
        if (target == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在");
        }
        requireName(command.itemName(), "检查项名称");
        if (target.getParentId() != null) {
            if ((command.items() != null && !command.items().isEmpty())
                    || (command.deletedItemIds() != null && !command.deletedItemIds().isEmpty())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "修改检查项小类时不能携带子项新增、修改或删除列表");
            }
            target.setItemName(command.itemName().trim());
            templateMapper.update(target);
            return new TemplateUpdateView(TemplateView.from(target), List.of());
        }

        target.setItemName(command.itemName().trim());
        templateMapper.update(target);

        List<TemplateView> items = new ArrayList<>();
        List<UpdateItemCommand> requestedItems = command.items() == null ? List.of() : command.items();
        validateChildNamesAfterUpdate(target, requestedItems, command.deletedItemIds());
        deleteRequestedChildren(target, requestedItems, command.deletedItemIds());
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
        return new TemplateUpdateView(TemplateView.from(target), List.copyOf(items));
    }

    /**
     * 只允许删除当前大类的启用子项。按原排序号倒序处理，避免连续删除多个子项后留下排序空档。
     */
    private void deleteRequestedChildren(CheckItemTemplateRecord category, List<UpdateItemCommand> requestedItems,
                                         List<Long> deletedItemIds) {
        if (deletedItemIds == null || deletedItemIds.isEmpty()) {
            return;
        }
        List<Long> ids = deletedItemIds.stream().filter(Objects::nonNull).toList();
        if (ids.size() != deletedItemIds.size() || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "待删除的子检查项 ID 不能为空且不能重复");
        }
        Set<Long> updatedIds = requestedItems.stream().map(UpdateItemCommand::itemId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (ids.stream().anyMatch(updatedIds::contains)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一子检查项不能同时修改和删除");
        }
        List<CheckItemTemplateRecord> children = templateMapper.findByIds(ids);
        if (children.size() != ids.size() || children.stream().anyMatch(child -> !Objects.equals(child.getParentId(), category.getId())
                || !category.getReviewType().equals(child.getReviewType()) || !Boolean.TRUE.equals(child.getEnabled()))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "待删除的检查项不存在、已停用或不属于当前类别");
        }
        children.stream().sorted(Comparator.comparing(CheckItemTemplateRecord::getSortNo).reversed()).forEach(child -> {
            templateMapper.disableById(child.getId());
            templateMapper.decrementSiblingItemSortAfter(category.getId(), child.getSortNo());
        });
    }

    /** 合并现有、待修改和待新增子项后统一校验，避免批量操作绕过同父类重名限制。 */
    private void validateChildNamesAfterUpdate(CheckItemTemplateRecord category, List<UpdateItemCommand> requestedItems,
                                               List<Long> deletedItemIds) {
        Set<Long> deletedIds = deletedItemIds == null ? Set.of() : new HashSet<>(deletedItemIds);
        Map<Long, String> namesById = new LinkedHashMap<>();
        List<CheckItemTemplateRecord> records = templateMapper.findAll(category.getReviewType());
        if (records != null) {
            records.stream().filter(record -> Objects.equals(record.getParentId(), category.getId()))
                    .filter(record -> !deletedIds.contains(record.getId()))
                    .forEach(record -> namesById.put(record.getId(), record.getItemName()));
        }
        long temporaryId = Long.MIN_VALUE;
        for (UpdateItemCommand item : requestedItems) {
            requireName(item.itemName(), "检查项名称");
            if (item.itemId() != null && !namesById.containsKey(item.itemId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不属于当前类别");
            }
            namesById.put(item.itemId() == null ? temporaryId++ : item.itemId(), item.itemName().trim());
        }
        requireDistinctChildItemNames(new ArrayList<>(namesById.values()));
    }

    private void requireDistinctChildItemNames(List<String> itemNames) {
        Set<String> names = new HashSet<>();
        for (String itemName : itemNames) {
            requireName(itemName, "检查项名称");
            if (!names.add(itemName.trim())) {
                throw duplicateChildItemName();
            }
        }
    }

    private BusinessException duplicateChildItemName() {
        return new BusinessException(ErrorCode.DUPLICATE_REQUEST, "同一检查项大类下不能存在同名子检查项");
    }


    @Transactional
    public void disable(long id, DeleteTargetCategory category, String operatorEmployeeNo, String operatorName) {
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
            templateMapper.decrementCategorySortAfter(template.getReviewType(), template.getSortNo());
        } else {
            templateMapper.decrementSiblingItemSortAfter(template.getParentId(), template.getSortNo());
        }
    }

    private List<ImportCategory> readCategories(byte[] workbookBytes, ReviewType reviewType) {
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "导入文件不能为空");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Excel 不包含工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            ImportColumns columns = readHeaders(sheet, reviewType);
            DataFormatter formatter = new DataFormatter();
            Map<Integer, List<String>> imagesByRow = embeddedImagesByRow(sheet);
            Map<String, ImportCategoryBuilder> categories = new LinkedHashMap<>();
            String previousCategoryName = null;
            for (int rowIndex = columns.headerRow() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter)) continue;
                String itemCell = cell(row, columns.itemColumn(), formatter);
                if (itemCell.isBlank() && !imagesByRow.containsKey(rowIndex)) continue;
                String categoryName = cell(row, columns.categoryColumn(), formatter);
                if (!categoryName.isBlank()) previousCategoryName = categoryName;
                else categoryName = previousCategoryName;
                if (categoryName == null || categoryName.isBlank()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "第 " + (rowIndex + 1) + " 行检查项缺少类别；合并类别单元格仅可继承上一条非空类别");
                }
                String normalizedCategoryName = categoryName.trim();
                List<String> itemNames = (columns.splitItemCell() ? splitEmbeddedItems(itemCell) : List.of(itemCell.trim())).stream()
                        .map(this::normalizeImportedItemName)
                        .filter(itemName -> !itemName.isBlank())
                        .toList();
                // “...”仅用于原始表格的后续内容占位，不属于可执行的检查项。
                if (itemNames.isEmpty()) {
                    continue;
                }
                ImportCategoryBuilder category = categories.computeIfAbsent(normalizedCategoryName,
                        ignored -> new ImportCategoryBuilder(reviewType, normalizedCategoryName, categories.size() + 1));
                List<String> images = imagesByRow.getOrDefault(rowIndex, List.of());
                for (int index = 0; index < itemNames.size(); index++) {
                    String itemName = itemNames.get(index);
                    String itemRichText = toItemRichText(itemName, index == itemNames.size() - 1 ? images : List.of());
                    category.add(itemName, itemRichText, rowIndex + 1);
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

    private ImportColumns readHeaders(Sheet sheet, ReviewType reviewType) {
        String categoryHeader = reviewType == ReviewType.PCB ? "类别" : "检查项类别";
        String itemHeader = reviewType == ReviewType.PCB ? "检查项" : "检查内容";
        DataFormatter formatter = new DataFormatter();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 30); rowIndex++) {
            Row header = sheet.getRow(rowIndex);
            if (header == null) continue;
            Map<String, Integer> headers = new HashMap<>();
            for (int cellIndex = header.getFirstCellNum(); cellIndex < header.getLastCellNum(); cellIndex++) {
                headers.put(formatter.formatCellValue(header.getCell(cellIndex)).trim(), cellIndex);
            }
            if (headers.containsKey(categoryHeader) && headers.containsKey(itemHeader)) {
                return new ImportColumns(rowIndex, headers.get(categoryHeader), headers.get(itemHeader), reviewType == ReviewType.PCB);
            }
            // 兼容历史上按 PCB 列名维护、但选择了原理图类型的通用模板；新原理图模板优先使用专属列名。
            if (reviewType == ReviewType.SCHEMATIC && headers.containsKey("类别") && headers.containsKey("检查项")) {
                return new ImportColumns(rowIndex, headers.get("类别"), headers.get("检查项"), true);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, reviewType == ReviewType.PCB
                ? "未找到 PCB 模板表头；需要“类别、检查项”列"
                : "未找到原理图模板表头；需要“检查项类别、检查内容”列");
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
            if (!formatter.formatCellValue(row.getCell(cellIndex)).isBlank()) return false;
        }
        return true;
    }

    private String cell(Row row, int columnIndex, DataFormatter formatter) {
        return row.getCell(columnIndex) == null ? "" : formatter.formatCellValue(row.getCell(columnIndex)).trim();
    }

    private Map<Integer, List<String>> embeddedImagesByRow(Sheet sheet) {
        if (!(sheet instanceof XSSFSheet xssfSheet) || xssfSheet.getDrawingPatriarch() == null) return Map.of();
        Map<Integer, List<String>> imagesByRow = new HashMap<>();
        for (XSSFShape shape : xssfSheet.getDrawingPatriarch().getShapes()) {
            if (!(shape instanceof XSSFPicture picture) || picture.getClientAnchor() == null) continue;
            int rowIndex = picture.getClientAnchor().getRow1();
            if (rowIndex < 0) continue;
            String extension = picture.getPictureData().suggestFileExtension();
            String mimeType = "jpg".equalsIgnoreCase(extension) || "jpeg".equalsIgnoreCase(extension) ? "image/jpeg" : "image/" + extension;
            String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(picture.getPictureData().getData());
            imagesByRow.computeIfAbsent(rowIndex, ignored -> new ArrayList<>()).add(dataUri);
        }
        return imagesByRow;
    }

    private String toItemRichText(String itemName, List<String> images) {
        StringBuilder richText = new StringBuilder(escapeHtml(itemName).replace("\n", "<br>"));
        for (String image : images) richText.append("<br><img src=\"").append(image).append("\" alt=\"检查项示例图\" />");
        return richText.toString();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 仅按行首编号拆分，避免把 1.5mm、12.5mm 等正文数值当作检查项编号。 */
    private List<String> splitEmbeddedItems(String itemCell) {
        String normalized = itemCell == null ? "" : itemCell.replace(' ', ' ').trim();
        if (normalized.isBlank()) return List.of();
        java.util.regex.Matcher matcher = ITEM_SERIAL_PATTERN.matcher(normalized);
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

    private String normalizeImportedItemName(String itemName) {
        String normalized = itemName == null ? "" : itemName.replace(' ', ' ').trim();
        if (isPlaceholderItem(normalized)) {
            return "";
        }
        String withoutPlaceholderLines = normalized.lines()
                .filter(line -> !isPlaceholderItem(line))
                .collect(java.util.stream.Collectors.joining("\n"))
                .trim();
        return ITEM_SERIAL_PATTERN.matcher(withoutPlaceholderLines).replaceFirst("").trim();
    }

    private boolean isPlaceholderItem(String itemName) {
        return itemName != null && itemName.replaceAll("[\\s.。…]+", "").isBlank();
    }

    private int nextCategorySort(ReviewType reviewType) {
        List<CheckItemTemplateRecord> records = templateMapper.findAll(reviewType.name());
        return (records == null ? List.<CheckItemTemplateRecord>of() : records).stream().filter(record -> record.getParentId() == null)
                .map(CheckItemTemplateRecord::getSortNo).filter(Objects::nonNull).max(Integer::compareTo).map(value -> value + 1).orElse(1);
    }

    private CheckItemTemplateRecord newTemplate(ReviewType reviewType, Long parentId, String itemName, int sortNo, boolean enabled) {
        return newTemplate(reviewType, parentId, itemName, null, sortNo, enabled);
    }

    private CheckItemTemplateRecord newTemplate(ReviewType reviewType, Long parentId, String itemName,
                                                String itemRichText, int sortNo, boolean enabled) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(templateMapper.nextId());
        record.setReviewType(reviewType.name());
        record.setParentId(parentId);
        record.setItemName(itemName.trim());
        record.setItemRichText(itemRichText);
        record.setSortNo(sortNo);
        record.setEnabled(enabled);
        record.setVersion(0L);
        return record;
    }

    private void requireName(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, fieldName + "不能为空");
    }

    public record CreateCategoryCommand(ReviewType reviewType, String categoryName, List<CreateItemCommand> items) { }
    public record CreateItemCommand(String itemName) { }
    public record UpdateTemplateCommand(long itemId, String itemName, List<UpdateItemCommand> items,
                                        List<Long> deletedItemIds) { }
    public record UpdateItemCommand(Long itemId, String itemName) { }
    public enum DeleteTargetCategory { CATEGORY, ITEM }
    public record ImportResult(int totalRows, int createdCount, int replacedCount) { }

    public record TemplateView(Long id, ReviewType reviewType, Long parentId, String itemName, String itemRichText, int sortNo) {
        static TemplateView from(CheckItemTemplateRecord record) {
            return new TemplateView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getParentId(), record.getItemName(), record.getItemRichText(), record.getSortNo());
        }
    }
    public record TemplateCategoryView(TemplateView category, List<TemplateView> items) { }
    /** 统一修改接口的返回模型；小类修改时 items 为空，大类修改时返回本次处理的子项。 */
    public record TemplateUpdateView(TemplateView item, List<TemplateView> items) { }
    /** 某一种评审类型的唯一互检单模板。 */
    public record TemplateListView(ReviewType reviewType, List<TemplateListCategoryView> categories) { }
    /** 当前类型的启用模板是否存在，以及该模板包含的大类和子项数量。 */
    public record TemplateExistenceView(ReviewType reviewType, boolean exists, int categoryCount, int itemCount) { }
    public record TemplateListCategoryView(TemplateListItemView category, List<TemplateListItemView> items) { }
    public record TemplateListItemView(Long id, String itemName, String itemRichText, int sortNo) {
        static TemplateListItemView from(CheckItemTemplateRecord record) {
            return new TemplateListItemView(record.getId(), record.getItemName(), record.getItemRichText(), record.getSortNo());
        }
    }

    private record ImportColumns(int headerRow, int categoryColumn, int itemColumn, boolean splitItemCell) { }
    private record ImportItem(String itemName, String itemRichText) { }
    private record ImportCategory(ReviewType reviewType, String categoryName, List<ImportItem> items, int sortNo) { }
    private static final class ImportCategoryBuilder {
        private final ReviewType reviewType;
        private final String categoryName;
        private final int sortNo;
        private final Set<String> itemNames = new java.util.LinkedHashSet<>();
        private final List<ImportItem> items = new ArrayList<>();
        private ImportCategoryBuilder(ReviewType reviewType, String categoryName, int sortNo) {
            this.reviewType = reviewType;
            this.categoryName = categoryName;
            this.sortNo = sortNo;
        }
        private void add(String itemName, String itemRichText, int displayRow) {
            if (!itemNames.add(itemName)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "第 " + displayRow + " 行存在重复检查项：" + itemName);
            }
            items.add(new ImportItem(itemName, itemRichText));
        }
        private ImportCategory toValue() { return new ImportCategory(reviewType, categoryName, List.copyOf(items), sortNo); }
    }
}
