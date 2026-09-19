package com.bms.audit.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义操作审计日志的追加式数据访问接口；审计记录一经写入不允许由业务流程更新或删除。
 */
@Mapper
public interface OperationAuditMapper {
    @Insert("INSERT INTO operation_audit_log (aggregate_type, aggregate_id, action, operator_id, detail) "
            + "VALUES (#{aggregateType}, #{aggregateId}, #{action}, #{operatorId}, #{detail})")
    int insert(OperationAuditRecord record);
}
