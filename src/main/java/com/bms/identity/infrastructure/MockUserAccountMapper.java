package com.bms.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 读取用户账号、角色定义与用户角色关联，为本地 Mock 身份模拟和人员下拉选择提供基础数据。
 */
@Mapper
public interface MockUserAccountMapper {
    @Select("SELECT id, display_name AS displayName, email, department_name AS departmentName, enabled FROM user_account WHERE enabled=TRUE ORDER BY id")
    List<MockUserAccountRecord> findEnabled();

    @Select("SELECT id, display_name AS displayName, email, department_name AS departmentName, enabled FROM user_account WHERE id=#{userId} AND enabled=TRUE")
    MockUserAccountRecord findEnabledById(long userId);

    @Select("SELECT ur.role_code FROM user_role ur JOIN role_definition rd ON rd.role_code=ur.role_code " +
            "WHERE ur.user_id=#{userId} AND rd.enabled=TRUE ORDER BY ur.role_code")
    List<String> findRoleCodesByUserId(long userId);
}
