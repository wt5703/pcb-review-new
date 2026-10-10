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
    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by_employee_no AS createdByEmployeeNo, created_at AS createdAt, deleted, deleted_by_employee_no AS deletedByEmployeeNo, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo} AND deleted=FALSE")
    ReviewerWhitelistRecord findByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by_employee_no AS createdByEmployeeNo, created_at AS createdAt, deleted, deleted_by_employee_no AS deletedByEmployeeNo, deleted_at AS deletedAt "
            + "FROM reviewer_whitelist WHERE review_role=#{reviewRole} AND employee_no=#{employeeNo}")
    ReviewerWhitelistRecord findAnyByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName "
            + "FROM reviewer_whitelist WHERE employee_no=#{employeeNo} AND deleted=FALSE ORDER BY id LIMIT 1")
    ReviewerWhitelistRecord findActiveByEmployeeNo(String employeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName "
            + "FROM reviewer_whitelist WHERE id=#{id} AND deleted=FALSE")
    ReviewerWhitelistRecord findById(long id);

    @Insert("INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by_employee_no) VALUES (#{reviewRole}, #{employeeNo}, #{displayName}, #{email}, #{mobile}, #{departmentName}, #{createdByEmployeeNo})")
    int insert(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET deleted=FALSE, deleted_by=NULL, deleted_at=NULL, display_name=#{displayName}, email=#{email}, mobile=#{mobile}, department_name=#{departmentName} WHERE id=#{id} AND deleted=TRUE")
    int restore(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET display_name=#{displayName}, email=#{email}, mobile=#{mobile}, department_name=#{departmentName} WHERE id=#{id} AND deleted=FALSE")
    int updateProfile(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=FALSE")
    int logicDeleteById(@Param("id") long id, @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Update("UPDATE reviewer_whitelist SET deleted=TRUE, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND deleted=FALSE")
    int logicDeleteByEmployeeNo(@Param("employeeNo") String employeeNo, @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Select("SELECT id, review_role AS reviewRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, department_name AS departmentName, created_by_employee_no AS createdByEmployeeNo, created_at AS createdAt, deleted, deleted_by_employee_no AS deletedByEmployeeNo, deleted_at AS deletedAt "
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
            + "department_name AS departmentName, created_by_employee_no AS createdByEmployeeNo, created_at AS createdAt, deleted, deleted_by_employee_no AS deletedByEmployeeNo, deleted_at AS deletedAt "
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
            + "SELECT review_role AS whitelistRole, employee_no AS employeeNo, display_name AS displayName, email, mobile, "
            + "department_name AS departmentName, created_at AS createdAt "
            + "FROM reviewer_whitelist WHERE deleted=FALSE AND review_role IN "
            + "<foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach> "
            + "ORDER BY review_role, id"
            + "</script>")
    List<AssignableReviewerRecord> findAssignableUsersByRoles(@Param("roles") List<String> roles);
}
