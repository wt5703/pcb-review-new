package com.bms.review.application;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
    private final CurrentUser manager = new CurrentUser(1L, Set.of(Role.PCB_LEADER));

    @Test
    void shouldDisableCategoryAndShiftFollowingCategorySortNumbers() {
        when(templateMapper.findById(100L)).thenReturn(template(100L, null, 2));

        service.disable(100L, CheckItemTemplateApplicationService.DeleteTargetCategory.CATEGORY, manager);

        verify(templateMapper).disableById(100L);
        verify(templateMapper).disableChildrenByParentId(100L);
        verify(templateMapper).decrementCategorySortAfter("PCB", 2);
        verify(templateMapper, never()).decrementSiblingItemSortAfter(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldDisableItemAndShiftFollowingSiblingSortNumbers() {
        when(templateMapper.findById(101L)).thenReturn(template(101L, 100L, 3));

        service.disable(101L, CheckItemTemplateApplicationService.DeleteTargetCategory.ITEM, manager);

        verify(templateMapper).disableById(101L);
        verify(templateMapper).decrementSiblingItemSortAfter(100L, 3);
        verify(templateMapper, never()).disableChildrenByParentId(101L);
        verify(templateMapper, never()).decrementCategorySortAfter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
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
