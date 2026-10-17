package com.bms.review.application;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import com.bms.review.infrastructure.AssignableReviewerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private final UserCenterUserProfileClient userProfileClient = mock(UserCenterUserProfileClient.class);
    private final ReviewerWhitelistApplicationService service = new ReviewerWhitelistApplicationService(whitelistMapper, userProfileClient);

    @BeforeEach
    void mockUserCenter() {
        when(userProfileClient.getByEmployeeNo(any())).thenAnswer(invocation -> {
            String number = invocation.getArgument(0);
            return new UserCenterUserProfileClient.UserProfile(number, number, null, null, null);
        });
    }

    @Test
    void shouldAddEmployeeNumbersForSupportedReviewRoles() {
        when(whitelistMapper.findAll()).thenReturn(List.of());
        ReviewerWhitelistApplicationService.SaveResult result = service.add(List.of(
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.HARDWARE_EXPERT, List.of("HW-1001", "HW-1002")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.EMC_EXPERT, List.of("EMC-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.STRUCTURE_EXPERT, List.of("STR-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.PROCESS_EXPERT, List.of("PRO-1001")),
                        new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.PCB_EXPERT, List.of("PCB-1001"))),
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(result.createdCount()).isEqualTo(6);
        verify(whitelistMapper, times(6)).insert(any());
        verify(whitelistMapper, times(6)).addOrRestoreRole(any(), any(), any());
    }

    @Test
    void shouldLogicallyDeleteEveryActiveRoleMappingForEmployeeNumber() {
        ReviewerWhitelistRecord person = person(1L, "PCB-1001", "PCB_EXPERT");
        when(whitelistMapper.lockByEmployeeNo("PCB-1001")).thenReturn(person);
        when(whitelistMapper.logicDeleteById(1L, "BMS001")).thenReturn(1);

        ReviewerWhitelistApplicationService.DeleteResult result = service.remove(null, "PCB-1001",
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(result.deletedCount()).isEqualTo(1);
        verify(whitelistMapper).logicDeleteRolesByEmployeeNo("PCB-1001", "BMS001");
    }

    @Test
    void shouldKeepOnePersonWhenAddingDifferentRoles() {
        ReviewerWhitelistRecord existingPerson = person(1L, "BMS002", null);
        when(whitelistMapper.lockByEmployeeNo("BMS002")).thenReturn(null, existingPerson);
        when(whitelistMapper.findAll()).thenReturn(List.of());
        var result = service.add(List.of(
                new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.HARDWARE_EXPERT, List.of("BMS002")),
                new ReviewerWhitelistApplicationService.RoleEmployeeNos(ReviewerWhitelistRole.EMC_EXPERT, List.of("BMS002"))),
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));
        assertThat(result.createdCount()).isEqualTo(2);
        verify(whitelistMapper, times(1)).insert(any());
        verify(whitelistMapper).addOrRestoreRole("BMS002", "HARDWARE_EXPERT", "BMS001");
        verify(whitelistMapper).addOrRestoreRole("BMS002", "EMC_EXPERT", "BMS001");
    }

    @Test
    void shouldKeepPersonWhenRemovingOneOfMultipleRoles() {
        when(whitelistMapper.lockActiveById(1L)).thenReturn(person(1L, "BMS002", null));
        when(whitelistMapper.logicDeleteRole("BMS002", "EMC_EXPERT", "BMS001")).thenReturn(1);
        when(whitelistMapper.countActiveRoles("BMS002")).thenReturn(1L);
        service.removeRole(1L, ReviewerWhitelistRole.EMC_EXPERT,
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));
        verify(whitelistMapper, times(0)).logicDeleteById(anyLong(), any());
    }

    @Test
    void shouldDisablePersonWhenRemovingLastRole() {
        when(whitelistMapper.lockActiveById(1L)).thenReturn(person(1L, "BMS002", null));
        when(whitelistMapper.logicDeleteRole("BMS002", "EMC_EXPERT", "BMS001")).thenReturn(1);
        when(whitelistMapper.countActiveRoles("BMS002")).thenReturn(0L);
        service.removeRole(1L, ReviewerWhitelistRole.EMC_EXPERT,
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));
        verify(whitelistMapper).logicDeleteById(1L, "BMS001");
    }

    @Test
    void shouldPagePeopleAndIncludeAllRoles() {
        ReviewerWhitelistRecord hardware = person(1L, "BMS002", "HARDWARE_EXPERT");
        ReviewerWhitelistRecord emc = person(1L, "BMS002", "EMC_EXPERT");
        when(whitelistMapper.countByKeyword("")).thenReturn(2L);
        when(whitelistMapper.findPageByKeyword("", 0, 1)).thenReturn(List.of(hardware));
        when(whitelistMapper.findRolesByEmployeeNos(List.of("BMS002"))).thenReturn(List.of(hardware, emc));
        var page = service.listPage(new ReviewerWhitelistApplicationService.ReviewerWhitelistQuery(null, 1, 1),
                new CurrentUser("BMS001", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).reviewRoles()).containsExactlyInAnyOrder(ReviewerWhitelistRole.HARDWARE_EXPERT, ReviewerWhitelistRole.EMC_EXPERT);
    }

    @Test
    void shouldFilterPeopleByRoleWithoutDuplicatingThemOrLosingOtherRoles() {
        ReviewerWhitelistRecord hardware = person(1L, "BMS002", "HARDWARE_EXPERT");
        ReviewerWhitelistRecord emc = person(1L, "BMS002", "EMC_EXPERT");
        when(whitelistMapper.findAll()).thenReturn(List.of(hardware, emc));
        var people = service.listPeople(List.of(ReviewerWhitelistRole.EMC_EXPERT, ReviewerWhitelistRole.HARDWARE_EXPERT));
        assertThat(people).hasSize(1);
        assertThat(people.get(0).reviewRoles()).containsExactlyInAnyOrder(ReviewerWhitelistRole.HARDWARE_EXPERT, ReviewerWhitelistRole.EMC_EXPERT);
    }

    @Test
    void shouldDeduplicateAssignablePeopleAcrossRoles() {
        AssignableReviewerRecord first = new AssignableReviewerRecord(); first.setEmployeeNo("BMS002"); first.setWhitelistRole("HARDWARE_EXPERT");
        AssignableReviewerRecord second = new AssignableReviewerRecord(); second.setEmployeeNo("BMS002"); second.setWhitelistRole("EMC_EXPERT");
        when(whitelistMapper.findAssignableUsersByRoles(any())).thenReturn(List.of(first, second));
        when(whitelistMapper.findRolesByEmployeeNos(List.of("BMS002"))).thenReturn(List.of(person(1L, "BMS002", "HARDWARE_EXPERT"), person(1L, "BMS002", "EMC_EXPERT")));
        var people = service.listAssignableUsers(List.of(ReviewerWhitelistRole.HARDWARE_EXPERT, ReviewerWhitelistRole.EMC_EXPERT));
        assertThat(people).hasSize(1);
        assertThat(people.get(0).whitelistRoles()).containsExactlyInAnyOrder(ReviewerWhitelistRole.HARDWARE_EXPERT, ReviewerWhitelistRole.EMC_EXPERT);
    }

    private ReviewerWhitelistRecord person(long id, String employeeNo, String role) {
        ReviewerWhitelistRecord record = new ReviewerWhitelistRecord();
        record.setId(id); record.setEmployeeNo(employeeNo); record.setReviewRole(role); record.setDisplayName(employeeNo);
        return record;
    }
}
