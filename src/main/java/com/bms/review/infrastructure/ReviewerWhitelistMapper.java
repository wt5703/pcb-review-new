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
    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo} AND deleted=FALSE")
    ReviewerWhitelistRecord findByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo}")
    ReviewerWhitelistRecord findAnyByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName "
            + "FROM reviewer_whitelist WHERE employee_no=#{employeeNo} AND deleted=FALSE ORDER BY id LIMIT 1")
    ReviewerWhitelistRecord findActiveByEmployeeNo(String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName "
            + "FROM reviewer_whitelist WHERE id=#{id} AND deleted=FALSE")
    ReviewerWhitelistRecord findById(long id);

    @Insert("INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by) VALUES (#{reviewRole}, #{employeeNo}, #{displayName}, #{email}, #{mobile}, #{departmentName}, #{createdBy})")
    int insert(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET deleted=FALSE, deleted_by=NULL, deleted_at=NULL WHERE id=#{id} AND deleted=TRUE")
    int restore(long id);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by=#{operatorId}, deleted_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=FALSE")
    int logicDeleteById(@Param("id") long id, @Param("operatorId") long operatorId);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by=#{operatorId}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND deleted=FALSE")
    int logicDeleteByEmployeeNo(@Param("employeeNo") String employeeNo, @Param("operatorId") long operatorId);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE deleted=FALSE ORDER BY review_role, employee_no")
    List<ReviewerWhitelistRecord> findAll();

    @Select("<script>"
            + "SELECT COUNT(*) FROM reviewer_whitelist WHERE deleted=FALSE "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (employee_no LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "</script>")
    long countByKeyword(@Param("keyword") String keyword);

    @Select("<script>"
            + "SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, "
            + "department_name AS departmentName, created_by AS createdBy, created_at AS createdAt, deleted, deleted_by AS deletedBy, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE deleted=FALSE "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (employee_no LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "ORDER BY review_role, employee_no, id LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ReviewerWhitelistRecord> findPageByKeyword(@Param("keyword") String keyword,
                                                      @Param("offset") int offset,
                                                      @Param("limit") int limit);

    @Select("<script>"
            + "SELECT id AS userId, review_role AS whitelistRole, employee_no AS employeeNo, "
            + "display_name AS displayName, department_name AS departmentName "
            + "FROM reviewer_whitelist WHERE deleted=FALSE AND review_role IN "
            + "<foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach> "
            + "ORDER BY review_role, id"
            + "</script>")
    List<AssignableReviewerRecord> findAssignableUsersByRoles(@Param("roles") List<String> roles);
}
