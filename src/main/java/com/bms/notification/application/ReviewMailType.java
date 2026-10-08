package com.bms.notification.application;

/** 邮件发送日志使用的明确业务类型，同时映射到 classpath 下的正文模板。 */
public enum ReviewMailType {
    PCB_TASK_CREATED("pcb-task-created"),
    PCB_OPTIONAL_REVIEW("pcb-optional-review"),
    PCB_TASK_FINISHED("pcb-task-finished"),
    SCHEMATIC_TASK_CREATED("schematic-task-created"),
    SCHEMATIC_TASK_FINISHED("schematic-task-finished");

    private final String templateName;

    ReviewMailType(String templateName) { this.templateName = templateName; }
    public String templateName() { return templateName; }
}
