package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 白名单人员资料与角色关联分别维护；所有目录查询只读取启用的人员及角色。 */
@Mapper
public interface ReviewerWhitelistMapper {
    String PERSON_COLUMNS = "p.id, p.employee_no AS employeeNo, p.display_name AS displayName, p.email, p.mobile, "
            + "p.department_name AS departmentName, p.`roles` AS roles, p.created_by_employee_no AS createdByEmployeeNo, p.created_at AS createdAt, "
            + "p.delete_status AS deleted, p.deleted_by_employee_no AS deletedByEmployeeNo, p.deleted_at AS deletedAt ";

    @Select("SELECT " + PERSON_COLUMNS + ", r.review_role AS reviewRole FROM reviewer_whitelist p JOIN reviewer_whitelist_role r ON r.employee_no=p.employee_no "
            + "WHERE p.delete_status=0 AND r.delete_status=0 AND r.review_role=#{reviewRole} AND p.employee_no=#{employeeNo}")
    ReviewerWhitelistRecord findByRoleAndEmployeeNo(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + ", r.review_role AS reviewRole FROM reviewer_whitelist p JOIN reviewer_whitelist_role r ON r.employee_no=p.employee_no "
            + "WHERE p.delete_status=0 AND r.delete_status=0 AND r.review_role=#{reviewRole} AND p.employee_no=#{employeeNo} FOR UPDATE")
    ReviewerWhitelistRecord lockActiveRole(@Param("reviewRole") String reviewRole, @Param("employeeNo") String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.employee_no=#{employeeNo} AND p.delete_status=0")
    ReviewerWhitelistRecord findActiveByEmployeeNo(String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.delete_status=0 AND p.roles IS NOT NULL ORDER BY p.id")
    List<ReviewerWhitelistRecord> findActiveWithSystemRoles();

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.delete_status=0 AND p.department_name=#{departmentName} ORDER BY p.id")
    List<ReviewerWhitelistRecord> findActiveByDepartmentName(@Param("departmentName") String departmentName);

    @Select("<script>SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.delete_status=0 AND p.employee_no IN "
            + "<foreach collection='employeeNos' item='employeeNo' open='(' separator=',' close=')'>#{employeeNo}</foreach>"
            + "</script>")
    List<ReviewerWhitelistRecord> findActiveByEmployeeNos(@Param("employeeNos") List<String> employeeNos);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.employee_no=#{employeeNo}")
    ReviewerWhitelistRecord findAnyByEmployeeNo(String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.employee_no=#{employeeNo} FOR UPDATE")
    ReviewerWhitelistRecord lockByEmployeeNo(String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.id=#{id} AND p.delete_status=0")
    ReviewerWhitelistRecord findById(long id);

    @Select("SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.id=#{id} AND p.delete_status=0 FOR UPDATE")
    ReviewerWhitelistRecord lockActiveById(long id);

    @Insert("INSERT INTO reviewer_whitelist (employee_no, display_name, email, mobile, department_name, `roles`, created_by_employee_no) VALUES (#{employeeNo}, #{displayName}, #{email}, #{mobile}, #{departmentName}, #{roles}, #{createdByEmployeeNo})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET delete_status=0, deleted_by_employee_no=NULL, deleted_at=NULL, display_name=#{displayName}, email=#{email}, mobile=#{mobile}, department_name=#{departmentName}, `roles`=#{roles} WHERE id=#{id} AND delete_status=1")
    int restore(ReviewerWhitelistRecord record);

    @Update("UPDATE reviewer_whitelist SET display_name=#{displayName}, email=#{email}, mobile=#{mobile}, department_name=#{departmentName}, `roles`=#{roles} WHERE id=#{id} AND delete_status=0")
    int updateProfile(ReviewerWhitelistRecord record);

    @Insert("INSERT INTO reviewer_whitelist_role (employee_no, review_role, created_by_employee_no) VALUES (#{employeeNo}, #{reviewRole}, #{operatorEmployeeNo}) "
            + "ON DUPLICATE KEY UPDATE delete_status=0, deleted_by_employee_no=NULL, deleted_at=NULL")
    int addOrRestoreRole(@Param("employeeNo") String employeeNo, @Param("reviewRole") String reviewRole,
                         @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Update("UPDATE reviewer_whitelist SET delete_status=1, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE id=#{id} AND delete_status=0")
    int logicDeleteById(@Param("id") long id, @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Update("UPDATE reviewer_whitelist SET delete_status=1, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND delete_status=0")
    int logicDeleteByEmployeeNo(@Param("employeeNo") String employeeNo, @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Update("UPDATE reviewer_whitelist_role SET delete_status=1, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND delete_status=0")
    int logicDeleteRolesByEmployeeNo(@Param("employeeNo") String employeeNo, @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Update("UPDATE reviewer_whitelist_role SET delete_status=1, deleted_by_employee_no=#{operatorEmployeeNo}, deleted_at=CURRENT_TIMESTAMP WHERE employee_no=#{employeeNo} AND review_role=#{reviewRole} AND delete_status=0")
    int logicDeleteRole(@Param("employeeNo") String employeeNo, @Param("reviewRole") String reviewRole,
                        @Param("operatorEmployeeNo") String operatorEmployeeNo);

    @Select("SELECT COUNT(*) FROM reviewer_whitelist_role WHERE employee_no=#{employeeNo} AND delete_status=0")
    long countActiveRoles(String employeeNo);

    @Select("SELECT " + PERSON_COLUMNS + ", r.review_role AS reviewRole FROM reviewer_whitelist p JOIN reviewer_whitelist_role r ON r.employee_no=p.employee_no "
            + "WHERE p.delete_status=0 AND r.delete_status=0 ORDER BY p.employee_no, r.review_role")
    List<ReviewerWhitelistRecord> findAll();

    @Select("<script>SELECT COUNT(*) FROM reviewer_whitelist p WHERE p.delete_status=0 "
            + "AND EXISTS (SELECT 1 FROM reviewer_whitelist_role r WHERE r.employee_no=p.employee_no AND r.delete_status=0) "
            + "<if test='keyword != null and keyword != &quot;&quot;'>AND (p.employee_no LIKE CONCAT('%', #{keyword}, '%') OR p.display_name LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countByKeyword(@Param("keyword") String keyword);

    @Select("<script>SELECT " + PERSON_COLUMNS + "FROM reviewer_whitelist p WHERE p.delete_status=0 "
            + "AND EXISTS (SELECT 1 FROM reviewer_whitelist_role r WHERE r.employee_no=p.employee_no AND r.delete_status=0) "
            + "<if test='keyword != null and keyword != &quot;&quot;'>AND (p.employee_no LIKE CONCAT('%', #{keyword}, '%') OR p.display_name LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY p.employee_no, p.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<ReviewerWhitelistRecord> findPageByKeyword(@Param("keyword") String keyword, @Param("offset") long offset, @Param("limit") int limit);

    @Select("<script>SELECT " + PERSON_COLUMNS + ", r.review_role AS reviewRole FROM reviewer_whitelist p JOIN reviewer_whitelist_role r ON r.employee_no=p.employee_no "
            + "WHERE p.delete_status=0 AND r.delete_status=0 AND p.employee_no IN "
            + "<foreach collection='employeeNos' item='employeeNo' open='(' separator=',' close=')'>#{employeeNo}</foreach> "
            + "ORDER BY p.employee_no, r.review_role</script>")
    List<ReviewerWhitelistRecord> findRolesByEmployeeNos(@Param("employeeNos") List<String> employeeNos);

    @Select("<script>SELECT r.review_role AS whitelistRole, p.employee_no AS employeeNo, p.display_name AS displayName, p.email, p.mobile, "
            + "p.department_name AS departmentName, p.created_at AS createdAt FROM reviewer_whitelist p JOIN reviewer_whitelist_role r ON r.employee_no=p.employee_no "
            + "WHERE p.delete_status=0 AND r.delete_status=0 AND r.review_role IN "
            + "<foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach> "
            + "ORDER BY p.employee_no, r.review_role</script>")
    List<AssignableReviewerRecord> findAssignableUsersByRoles(@Param("roles") List<String> roles);
}
