package com.bms.review.application;

import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import com.bms.task.domain.ReviewType;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-25
 * @description 验证互检模板逻辑删除后会同步重排受影响的同级展示序号。
 */
class CheckItemTemplateApplicationServiceTest {
    private final CheckItemTemplateMapper templateMapper = mock(CheckItemTemplateMapper.class);
    private final CheckItemTemplateApplicationService service = new CheckItemTemplateApplicationService(templateMapper);

    @Test
    void shouldDisableCategoryAndShiftFollowingCategorySortNumbers() {
        when(templateMapper.findById(100L)).thenReturn(template(100L, null, 2));

        service.disable(100L, CheckItemTemplateApplicationService.DeleteTargetCategory.CATEGORY, "BMS001", "BMS001");

        verify(templateMapper).disableById(100L);
        verify(templateMapper).disableChildrenByParentId(100L);
        verify(templateMapper).decrementCategorySortAfter("PCB", 2);
        verify(templateMapper, never()).decrementSiblingItemSortAfter(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldDisableItemAndShiftFollowingSiblingSortNumbers() {
        when(templateMapper.findById(101L)).thenReturn(template(101L, 100L, 3));

        service.disable(101L, CheckItemTemplateApplicationService.DeleteTargetCategory.ITEM, "BMS001", "BMS001");

        verify(templateMapper).disableById(101L);
        verify(templateMapper).decrementSiblingItemSortAfter(100L, 3);
        verify(templateMapper, never()).disableChildrenByParentId(101L);
        verify(templateMapper, never()).decrementCategorySortAfter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldDeleteRequestedChildrenInDescendingSortOrder() {
        when(templateMapper.findById(100L)).thenReturn(template(100L, null, 1));
        CheckItemTemplateRecord second = template(102L, 100L, 2);
        CheckItemTemplateRecord fourth = template(104L, 100L, 4);
        second.setEnabled(true);
        fourth.setEnabled(true);
        when(templateMapper.findByIds(List.of(102L, 104L))).thenReturn(List.of(second, fourth));

        service.update(new CheckItemTemplateApplicationService.UpdateTemplateCommand(
                100L, "大类", List.of(), List.of(102L, 104L)), "BMS001", "测试用户");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(templateMapper);
        order.verify(templateMapper).disableById(104L);
        order.verify(templateMapper).decrementSiblingItemSortAfter(100L, 4);
        order.verify(templateMapper).disableById(102L);
        order.verify(templateMapper).decrementSiblingItemSortAfter(100L, 2);
    }

    @Test
    void shouldAppendNewItemToSpecifiedCategory() {
        CheckItemTemplateRecord category = template(100L, null, 1);
        category.setEnabled(true);
        CheckItemTemplateRecord existingItem = template(101L, 100L, 2);
        when(templateMapper.findById(100L)).thenReturn(category);
        when(templateMapper.findAll("PCB")).thenReturn(List.of(category, existingItem));
        when(templateMapper.nextId()).thenReturn(102L);

        var created = service.createItem(100L, "新增小项");

        org.assertj.core.api.Assertions.assertThat(created.parentId()).isEqualTo(100L);
        org.assertj.core.api.Assertions.assertThat(created.sortNo()).isEqualTo(3);
        verify(templateMapper).insert(org.mockito.ArgumentMatchers.argThat(item -> item.getId().equals(102L)
                && item.getParentId().equals(100L) && item.getSortNo().equals(3)
                && "新增小项".equals(item.getItemName())));
    }

    @Test
    void shouldRejectDuplicateNamesWhenCreatingItemsInBatch() {
        assertThatThrownBy(() -> service.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                ReviewType.PCB, "大类", List.of(
                new CheckItemTemplateApplicationService.CreateItemCommand("重复小项"),
                new CheckItemTemplateApplicationService.CreateItemCommand(" 重复小项 "))), "BMS001", "测试用户"))
                .hasMessage("同一检查项大类下不能存在同名子检查项");
    }

    @Test
    void shouldRejectDuplicateCategoryNameWhenCreatingTemplateCategory() {
        when(templateMapper.findCategoryByReviewTypeAndName("PCB", "已有大类"))
                .thenReturn(template(100L, null, 1));

        assertThatThrownBy(() -> service.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                ReviewType.PCB, "已有大类", List.of()), "BMS001", "测试用户"))
                .hasMessage("同一评审类型下不能存在同名检查项大类");
    }

    @Test
    void shouldRejectDuplicateNameWhenCreatingSingleItem() {
        CheckItemTemplateRecord category = template(100L, null, 1);
        category.setEnabled(true);
        when(templateMapper.findById(100L)).thenReturn(category);
        when(templateMapper.findChildByParentIdAndName(100L, "已有小项")).thenReturn(template(101L, 100L, 1));

        assertThatThrownBy(() -> service.createItem(100L, "已有小项"))
                .hasMessage("同一检查项大类下不能存在同名子检查项");
    }

    private CheckItemTemplateRecord template(long id, Long parentId, int sortNo) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(id);
        record.setParentId(parentId);
        record.setReviewType("PCB");
        record.setItemName("测试检查项");
        record.setSortNo(sortNo);
        return record;
    }
}
