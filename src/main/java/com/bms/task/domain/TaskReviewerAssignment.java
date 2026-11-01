package com.bms.task.domain;

import com.bms.review.domain.ReviewRole;

import java.util.List;
import java.util.Objects;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 任务中一类评审职责及对应专家。原理图的硬件、EMC 专家由硬件部开发经理在互检结束后写入该聚合字段。
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
