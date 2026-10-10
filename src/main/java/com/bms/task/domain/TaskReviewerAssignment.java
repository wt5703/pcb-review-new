package com.bms.task.domain;

import com.bms.review.domain.ReviewRole;

import java.util.List;
import java.util.Objects;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 任务创建时确认的一类评审职责及对应专家。它是 review_task 的聚合字段，不建立独立任务评审人表。
 */
public record TaskReviewerAssignment(ReviewRole reviewRole, List<String> reviewerEmployeeNos) {
    public TaskReviewerAssignment {
        reviewRole = Objects.requireNonNull(reviewRole, "评审角色不能为空");
        reviewerEmployeeNos = reviewerEmployeeNos == null ? List.of() : reviewerEmployeeNos.stream()
                .filter(employeeNo -> employeeNo != null && !employeeNo.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
