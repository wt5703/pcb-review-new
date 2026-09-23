package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 持久化评审角色与员工工号的一对多白名单关系，不与具体任务的评审分配记录混用。
 */
@Mapper
public interface ReviewerWhitelistMapper {
    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo} AND deleted=FALSE")
    ReviewerWhitelistRecord findByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo}")
    ReviewerWhitelistRecord findAnyByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Insert("INSERT INTO reviewer_whitelist (review_role, employee_no, created_by) VALUES (#{reviewRole}, #{employeeNo}, #{createdBy})")
    int insert(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET deleted=FALSE, deleted_by=NULL, deleted_at=NULL WHERE id=#{id} AND deleted=TRUE")
    int restore(long id);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by=#{operatorId}, deleted_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=FALSE")
    int logicDeleteById(@Param("id") long id, @Param("operatorId") long operatorId);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by=#{operatorId}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND deleted=FALSE")
    int logicDeleteByEmployeeNo(@Param("employeeNo") String employeeNo, @Param("operatorId") long operatorId);

    @Select("SELECT rw.id, rw.review_role AS reviewRole, rw.employee_no AS employeeNo, rw.created_by AS createdBy, rw.created_at AS createdAt, rw.deleted, rw.deleted_by AS deletedBy, rw.deleted_at AS deletedAt, "
            + "ua.display_name AS displayName, ua.email, ua.mobile FROM reviewer_whitelist rw "
            + "LEFT JOIN user_account ua ON ua.employee_no=rw.employee_no WHERE rw.deleted=FALSE ORDER BY rw.review_role, rw.employee_no")
    List<ReviewerWhitelistRecord> findAll();

    /**
     * 将白名单工号解析为当前可用的本地用户账号。生产环境替换身份目录实现时，保留这一查询契约即可。
     */
    @Select("<script>"
            + "SELECT rw.review_role AS whitelistRole, ua.id AS userId, ua.employee_no AS employeeNo, "
            + "ua.display_name AS displayName, ua.department_name AS departmentName "
            + "FROM reviewer_whitelist rw JOIN user_account ua ON ua.employee_no=rw.employee_no "
            + "WHERE rw.deleted=FALSE AND ua.enabled=TRUE AND rw.review_role IN "
            + "<foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach> "
            + "ORDER BY rw.review_role, ua.id"
            + "</script>")
    List<AssignableReviewerRecord> findAssignableUsersByRoles(@Param("roles") List<String> roles);
}
