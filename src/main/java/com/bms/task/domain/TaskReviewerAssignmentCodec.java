package com.bms.task.domain;

import com.bms.review.domain.ReviewRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 负责任务评审角色与专家映射的轻量序列化，避免重新引入已废弃的任务评审人关联表。
 */
public final class TaskReviewerAssignmentCodec {
    private TaskReviewerAssignmentCodec() { }

    /** 格式为 ROLE:userId,userId;ROLE:userId，角色和用户 ID 均来自受控枚举或数字。 */
    public static String encode(List<TaskReviewerAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return "";
        }
        return assignments.stream()
                .map(assignment -> assignment.reviewRole().name() + ":" + assignment.reviewerIds().stream()
                        .map(String::valueOf).collect(Collectors.joining(",")))
                .collect(Collectors.joining(";"));
    }

    /** 兼容历史任务的空字段；非法历史片段忽略，不阻塞既有任务查看。 */
    public static List<TaskReviewerAssignment> decode(String persistedValue) {
        if (persistedValue == null || persistedValue.isBlank()) {
            return List.of();
        }
        List<TaskReviewerAssignment> assignments = new ArrayList<>();
        for (String segment : persistedValue.split(";")) {
            String[] pair = segment.split(":", 2);
            if (pair.length != 2 || pair[0].isBlank()) {
                continue;
            }
            try {
                ReviewRole role = ReviewRole.valueOf(pair[0].trim());
                List<Long> reviewerIds = pair[1].isBlank() ? List.of() : java.util.Arrays.stream(pair[1].split(","))
                        .filter(value -> !value.isBlank())
                        .map(String::trim)
                        .map(Long::valueOf)
                        .distinct()
                        .toList();
                assignments.add(new TaskReviewerAssignment(role, reviewerIds));
            } catch (IllegalArgumentException ignored) {
                // 历史数据异常时按无该角色处理，避免单条旧数据阻塞任务列表。
            }
        }
        return List.copyOf(assignments);
    }

    public static String flattenReviewerIds(List<TaskReviewerAssignment> assignments) {
        if (assignments == null) {
            return "";
        }
        return assignments.stream().flatMap(assignment -> assignment.reviewerIds().stream())
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * assigned_reviewer_ids 是当前流程节点仍待处理人员的快照，和创建时的角色—人员配置分开保存。
     * 使用 LinkedHashSet 保持人员加入顺序，便于接口返回和排查流程流转。
     */
    public static List<Long> decodeReviewerIds(String persistedValue) {
        if (persistedValue == null || persistedValue.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String value : persistedValue.split(",")) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(value.trim()));
            } catch (NumberFormatException ignored) {
                // 容错读取历史脏数据，避免单条任务阻塞待办列表。
            }
        }
        return List.copyOf(ids);
    }

    public static String encodeReviewerIds(Collection<Long> reviewerIds) {
        if (reviewerIds == null || reviewerIds.isEmpty()) {
            return "";
        }
        return reviewerIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
