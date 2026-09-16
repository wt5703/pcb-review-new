package com.leapmotor.pcbreview.task.domain;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 区分 PCB 与原理图两类评审任务，为提交字段校验、人员分配方式及后续流程编排提供明确的业务分支依据。
 */


public enum ReviewType {
    PCB,
    SCHEMATIC
}
