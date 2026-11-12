package com.bms.workflow.interfaces;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.workflow.application.WorkflowApplicationService.TransitionBatchCommand;
import com.bms.workflow.application.WorkflowApplicationService.TransitionFileCommand;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将流程表单中的动作、人员和文件转换为应用服务命令。 */
@Component
final class WorkflowTransitionRequestMapper {
    private final ReviewerWhitelistApplicationService whitelist;

    WorkflowTransitionRequestMapper(ReviewerWhitelistApplicationService whitelist) {
        this.whitelist = whitelist;
    }

    MappedTransition map(TransitionRequest request) {
        TransitionBatchCommand command = toBatchCommand(request);
        List<TransitionFileCommand> files = request.getFiles().stream()
                .map(file -> new TransitionFileCommand(file.getFileCategory(), file.getFile()))
                .toList();
        return new MappedTransition(command, files);
    }

    private TransitionBatchCommand toBatchCommand(TransitionRequest request) {
        if (request.getActions().size() != 1) {
            return withoutReviewerAssignments(request);
        }
        return switch (request.getActions().get(0)) {
            case START_SCHEMATIC_EXPERT_REVIEW -> withSchematicExperts(request);
            case START_PCB_MATUAL_REVIEW -> withMutualCheckReviewers(request, ReviewerWhitelistRole.PCB_MUTUAL_CHECK);
            case START_SCHEMATIC_MATUAL_REVIEW -> withMutualCheckReviewers(request, ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK);
            default -> withoutReviewerAssignments(request);
        };
    }

    private TransitionBatchCommand withSchematicExperts(TransitionRequest request) {
        if (!request.getReviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "原理图专家分配必须按 reviewerAssignments 传递角色和人员");
        }
        List<TaskReviewerAssignment> assignments = request.getReviewerAssignments().stream()
                .map(assignment -> new TaskReviewerAssignment(assignment.getReviewRole(),
                        resolveEmployeeNos(ReviewerWhitelistRole.fromReviewRole(assignment.getReviewRole()),
                                assignment.getReviewerEmployeeNos())))
                .toList();
        return new TransitionBatchCommand(request.getActions(), request.getComment(), List.of(), assignments);
    }

    private TransitionBatchCommand withMutualCheckReviewers(TransitionRequest request, ReviewerWhitelistRole role) {
        return new TransitionBatchCommand(request.getActions(), request.getComment(),
                resolveEmployeeNos(role, request.getReviewerEmployeeNos()));
    }

    private TransitionBatchCommand withoutReviewerAssignments(TransitionRequest request) {
        if (!request.getReviewerAssignments().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持按评审角色分配人员");
        }
        if (!request.getReviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
        }
        return new TransitionBatchCommand(request.getActions(), request.getComment(), List.of());
    }

    private List<String> resolveEmployeeNos(ReviewerWhitelistRole role, List<String> employeeNos) {
        return whitelist.findByRoleAndEmployeeNos(role, employeeNos).stream()
                .map(ReviewerWhitelistApplicationService.WhitelistPersonView::employeeNo)
                .toList();
    }

    record MappedTransition(TransitionBatchCommand command, List<TransitionFileCommand> files) { }
}
