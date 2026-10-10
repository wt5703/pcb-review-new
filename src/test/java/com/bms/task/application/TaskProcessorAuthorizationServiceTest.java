package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskProcessorAuthorizationServiceTest {
    private final TaskAssignmentAccessMapper accessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskProcessorAuthorizationService service = new TaskProcessorAuthorizationService(accessMapper);

    @Test
    void shouldRejectUserWhoIsNotCurrentTaskProcessor() {
        CurrentUser user = new CurrentUser("BMS088", Set.of(Role.PROCESS_EXPERT));
        when(accessMapper.isCurrentTaskProcessor(1001L, "BMS088")).thenReturn(false);

        assertThatThrownBy(() -> service.requireCurrentTaskProcessor(1001L, user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前任务节点不需要该用户处理");
    }
}
