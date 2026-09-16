package com.leapmotor.pcbreview.domain;

import com.leapmotor.pcbreview.task.domain.ReviewTask;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 评审任务生命周期领域测试类。
 */


class TaskLifecycleTest {

    @Test
    void pcbTaskMustHavePcbTypeAndInitialFileBeforeSubmit() {
        ReviewTask task = ReviewTask.draft(1L, ReviewType.PCB, "任务", "项目", 20L, "PCB-A", null);

        assertThatThrownBy(task::submit).isInstanceOf(IllegalStateException.class);

        task.setPcbType("BMU");
        task.addInitialFile(100L);
        task.submit();

        assertThat(task.status()).isEqualTo(TaskStatus.PCB_PENDING_REVIEW);
    }

    @Test
    void finishedTaskIsReadOnly() {
        ReviewTask task = ReviewTask.draft(1L, ReviewType.SCHEMATIC, "任务", "项目", 20L, "SCH-A", null);
        task.addInitialFile(100L);
        task.submit();
        task.moveTo(TaskStatus.PENDING_FINISH_CONFIRMATION);
        task.finish();

        assertThatThrownBy(() -> task.addInitialFile(101L)).isInstanceOf(IllegalStateException.class);
    }
}
