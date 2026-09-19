package com.bms.identity.application;

import com.bms.common.BusinessException;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 验证当前节点处理人校验仅允许已分配且尚未提交的评审人员执行处理动作，防止通过修改任务标识越权操作。
 */
class TaskNodeAuthorizationServiceTest {
    private final TaskAssignmentAccessMapper accessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskNodeAuthorizationService service = new TaskNodeAuthorizationService(accessMapper);

    @Test
    void shouldRejectUserWhoIsNotCurrentTaskProcessor() {
        CurrentUser user = new CurrentUser(88L, Set.of(Role.PROCESS_EXPERT));
        when(accessMapper.isCurrentTaskProcessor(1001L, 88L)).thenReturn(false);

        assertThatThrownBy(() -> service.requireCurrentTaskProcessor(1001L, user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前任务节点不需要该用户处理");
    }
}
