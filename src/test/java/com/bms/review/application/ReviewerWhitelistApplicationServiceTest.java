package com.bms.review.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 验证白名单按评审角色和员工工号一对多保存，且单次请求可以同时处理五类允许的评审角色。
 */
class ReviewerWhitelistApplicationServiceTest {
    private final ReviewerWhitelistMapper whitelistMapper = mock(ReviewerWhitelistMapper.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final ReviewerWhitelistApplicationService service = new ReviewerWhitelistApplicationService(whitelistMapper, auditMapper);

    @Test
    void shouldAddEmployeeNumbersForSupportedReviewRoles() {
        when(whitelistMapper.findAll()).thenReturn(List.of());
        ReviewerWhitelistApplicationService.SaveResult result = service.add(List.of(
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewRole.HARDWARE_EXPERT, List.of("HW-1001", "HW-1002")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewRole.EMC_EXPERT, List.of("EMC-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewRole.STRUCTURE_EXPERT, List.of("STR-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewRole.PROCESS_EXPERT, List.of("PRO-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewRole.PCB_EXPERT, List.of("PCB-1001"))),
                new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(result.createdCount()).isEqualTo(6);
        verify(whitelistMapper, times(6)).insert(any());
        verify(auditMapper).insert(any());
    }

    @Test
    void shouldLogicallyDeleteEveryActiveRoleMappingForEmployeeNumber() {
        when(whitelistMapper.logicDeleteByEmployeeNo("PCB-1001", 1L)).thenReturn(2);

        ReviewerWhitelistApplicationService.DeleteResult result = service.remove(null, "PCB-1001",
                new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(result.deletedCount()).isEqualTo(2);
        verify(whitelistMapper).logicDeleteByEmployeeNo("PCB-1001", 1L);
        verify(auditMapper).insert(any());
    }
}
