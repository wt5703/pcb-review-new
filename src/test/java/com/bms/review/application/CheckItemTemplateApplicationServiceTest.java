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

        service.disable(100L, CheckItemTemplateApplicationService.DeleteTargetCategory.CATEGORY);

        verify(templateMapper).disableById(100L);
        verify(templateMapper).disableChildrenByParentId(100L);
        verify(templateMapper).decrementCategorySortAfter("PCB", 2);
        verify(templateMapper, never()).decrementSiblingItemSortAfter(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldDisableItemAndShiftFollowingSiblingSortNumbers() {
        when(templateMapper.findById(101L)).thenReturn(template(101L, 100L, 3));

        service.disable(101L, CheckItemTemplateApplicationService.DeleteTargetCategory.ITEM);

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
                100L, "大类", List.of(), List.of(102L, 104L)));

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
                ReviewType.PCB, 1L, "大类", List.of(
                new CheckItemTemplateApplicationService.CreateItemCommand("重复小项"),
                new CheckItemTemplateApplicationService.CreateItemCommand(" 重复小项 ")))))
                .hasMessage("同一检查项大类下不能存在同名子检查项");
    }

    @Test
    void shouldRejectDuplicateCategoryNameWhenCreatingTemplateCategory() {
        CheckItemTemplateRecord root = template(1L, null, 1);
        root.setEnabled(true);
        when(templateMapper.findById(1L)).thenReturn(root);
        when(templateMapper.findChildByParentIdAndName(1L, "已有大类"))
                .thenReturn(template(100L, null, 1));

        assertThatThrownBy(() -> service.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                ReviewType.PCB, 1L, "已有大类", List.of())))
                .hasMessage("同一 Sheet 下不能存在同名检查项大类");
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

    @Test
    void shouldCreateThreeLevelsWithSheetScopedOrdering() {
        CheckItemTemplateRecord root = template(1L, null, 1);
        root.setEnabled(true);
        when(templateMapper.findById(1L)).thenReturn(root);
        when(templateMapper.findAll("PCB")).thenReturn(List.of(template(2L, 1L, 3), template(3L, 99L, 20)));
        when(templateMapper.nextId()).thenReturn(10L, 11L);
        var result = service.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                ReviewType.PCB, 1L, "大类", List.of(new CheckItemTemplateApplicationService.CreateItemCommand("小类"))));
        org.assertj.core.api.Assertions.assertThat(result.category().parentId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(result.category().sortNo()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(result.items().get(0).parentId()).isEqualTo(10L);
    }

    @Test
    void shouldRejectInvalidSheetParentsWithoutWriting() {
        for (int scenario = 0; scenario < 5; scenario++) {
            CheckItemTemplateRecord root = template(1L, scenario == 2 ? 9L : null, 1);
            root.setEnabled(scenario != 1);
            if (scenario == 3) root.setReviewType("SCHEMATIC");
            when(templateMapper.findById(1L)).thenReturn(scenario == 0 ? null : root);
            Long parentId = scenario == 4 ? null : 1L;
            assertThatThrownBy(() -> service.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                    ReviewType.PCB, parentId, "大类", List.of())))
                    .isInstanceOf(com.bms.common.BusinessException.class);
        }
        verify(templateMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldUpdateSecondLevelCategoryAndItsChildren() {
        CheckItemTemplateRecord root = template(1L, null, 1);
        CheckItemTemplateRecord category = template(2L, 1L, 1);
        CheckItemTemplateRecord child = template(3L, 2L, 1);
        when(templateMapper.findById(1L)).thenReturn(root);
        when(templateMapper.findById(2L)).thenReturn(category);
        when(templateMapper.findById(3L)).thenReturn(child);
        when(templateMapper.findAll("PCB")).thenReturn(List.of(root, category, child));
        when(templateMapper.nextId()).thenReturn(4L);
        var result = service.update(new CheckItemTemplateApplicationService.UpdateTemplateCommand(2L, "大类新名称",
                List.of(new CheckItemTemplateApplicationService.UpdateItemCommand(3L, "已有小类"),
                        new CheckItemTemplateApplicationService.UpdateItemCommand(null, "新增小类")), List.of()));
        org.assertj.core.api.Assertions.assertThat(result.items()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(result.item().parentId()).isEqualTo(1L);
        verify(templateMapper).update(child);
        verify(templateMapper).insert(org.mockito.ArgumentMatchers.argThat(item -> Long.valueOf(2L).equals(item.getParentId())));
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
