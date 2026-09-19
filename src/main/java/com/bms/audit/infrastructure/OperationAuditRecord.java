package com.bms.audit.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 对应 operation_audit_log 表的一条不可变业务操作记录，保存操作对象、操作者、动作类型与可追溯的业务摘要，不参与领域状态计算。
 */
public record OperationAuditRecord(String aggregateType, Long aggregateId, String action, Long operatorId, String detail) {
}
