package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import org.springframework.stereotype.Service;

/**
 * 任务域的当前处理人授权校验。
 *
 * <p>该校验依赖任务当前待处理人员集合，属于任务域；评审域只复用该能力，
 * 不再由 identity 模块持有任务数据访问依赖。</p>
 */
@Service
public class TaskProcessorAuthorizationService {
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;

    public TaskProcessorAuthorizationService(TaskAssignmentAccessMapper taskAssignmentAccessMapper) {
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
    }

    public void requireCurrentTaskProcessor(long taskId, CurrentUser currentUser) {
        if (!taskAssignmentAccessMapper.isCurrentTaskProcessor(taskId, currentUser.employeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前任务节点不需要该用户处理");
        }
    }
}
