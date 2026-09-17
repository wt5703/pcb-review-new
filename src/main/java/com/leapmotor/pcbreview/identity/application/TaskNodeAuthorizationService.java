package com.leapmotor.pcbreview.identity.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import org.springframework.stereotype.Service;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 校验当前用户是否为任务当前节点仍待处理的已分配人员，供评审、互检和确认等处理型写接口复用，不替代各接口的功能权限和状态校验。
 */
@Service
public class TaskNodeAuthorizationService {
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;

    public TaskNodeAuthorizationService(TaskAssignmentAccessMapper taskAssignmentAccessMapper) {
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
    }

    public void requireCurrentTaskProcessor(long taskId, CurrentUser currentUser) {
        if (!taskAssignmentAccessMapper.isCurrentTaskProcessor(taskId, currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前任务节点不需要该用户处理");
        }
    }
}
