package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.review.infrastructure.AssignableReviewerRecord;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 维护 PCB 评审可选人员白名单，以评审角色和员工工号建立一对多关系，供后续人员选择与校验使用。
 */
@Service
public class ReviewerWhitelistApplicationService {
    private static final List<ReviewerWhitelistRole> SUPPORTED_ROLE_ORDER = List.of(
            ReviewerWhitelistRole.HARDWARE_EXPERT,
            ReviewerWhitelistRole.EMC_EXPERT,
            ReviewerWhitelistRole.STRUCTURE_EXPERT,
            ReviewerWhitelistRole.PROCESS_EXPERT,
            ReviewerWhitelistRole.PCB_EXPERT,
            ReviewerWhitelistRole.PCB_MUTUAL_CHECK,
            ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK);
    private static final Set<ReviewerWhitelistRole> SUPPORTED_ROLES = Set.copyOf(SUPPORTED_ROLE_ORDER);

    private final ReviewerWhitelistMapper whitelistMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public ReviewerWhitelistApplicationService(ReviewerWhitelistMapper whitelistMapper) {
        this.whitelistMapper = whitelistMapper;
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 批量新增评审白名单映射。相同角色和工号的重复请求幂等忽略，避免产生重复数据。
     */
    @Transactional
    public SaveResult add(List<RoleEmployeeNos> roleEmployeeNos, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        if (roleEmployeeNos == null || roleEmployeeNos.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要提供一类评审人员白名单");
        }
        Set<ReviewerWhitelistRole> duplicateRoles = roleEmployeeNos.stream().map(RoleEmployeeNos::reviewRole).collect(Collectors.toSet());
        if (duplicateRoles.size() != roleEmployeeNos.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一评审角色只能提交一次");
        }
        int createdCount = 0;
        for (RoleEmployeeNos roleEmployeeNo : roleEmployeeNos) {
            if (!SUPPORTED_ROLES.contains(roleEmployeeNo.reviewRole())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单仅支持硬件、EMC、结构、工艺、PCB 评审及 PCB/原理图互检职责");
            }
            Set<String> employeeNos = roleEmployeeNo.employeeNos().stream()
                    .map(String::trim)
                    .collect(Collectors.toSet());
            if (employeeNos.size() != roleEmployeeNo.employeeNos().size()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一角色下员工工号不能重复");
            }
            for (String employeeNo : employeeNos) {
                if (whitelistMapper.findByRoleAndEmployeeNo(roleEmployeeNo.reviewRole().name(), employeeNo) != null) {
                    continue;
                }
                ReviewerWhitelistRecord historicalRecord = whitelistMapper.findAnyByRoleAndEmployeeNo(roleEmployeeNo.reviewRole().name(), employeeNo);
                if (historicalRecord != null) {
                    whitelistMapper.restore(historicalRecord.getId());
                    createdCount++;
                    continue;
                }
                ReviewerWhitelistRecord record = new ReviewerWhitelistRecord();
                record.setReviewRole(roleEmployeeNo.reviewRole().name());
                record.setEmployeeNo(employeeNo);
                ReviewerWhitelistRecord existingPerson = whitelistMapper.findActiveByEmployeeNo(employeeNo);
                record.setDisplayName(existingPerson == null ? employeeNo : existingPerson.getDisplayName());
                record.setEmail(existingPerson == null ? null : existingPerson.getEmail());
                record.setMobile(existingPerson == null ? null : existingPerson.getMobile());
                record.setDepartmentName(existingPerson == null ? null : existingPerson.getDepartmentName());
                record.setCreatedBy(currentUser.id());
                whitelistMapper.insert(record);
                createdCount++;
            }
        }
        return new SaveResult(createdCount, views());
    }

    @Transactional
    public DeleteResult remove(Long id, String employeeNo, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        boolean hasId = id != null;
        boolean hasEmployeeNo = employeeNo != null && !employeeNo.isBlank();
        if (hasId == hasEmployeeNo) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除白名单时必须且只能传主键 id 或员工工号 employeeNo");
        }
        int deletedCount = hasId
                ? whitelistMapper.logicDeleteById(id, currentUser.id())
                : whitelistMapper.logicDeleteByEmployeeNo(employeeNo.trim(), currentUser.id());
        if (deletedCount == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到可删除的白名单记录");
        }
        return new DeleteResult(deletedCount);
    }

    public List<RoleEmployeeNosView> views() {
        Map<ReviewerWhitelistRole, List<String>> employeeNosByRole = whitelistMapper.findAll().stream()
                .collect(Collectors.groupingBy(record -> ReviewerWhitelistRole.valueOf(record.getReviewRole()),
                        Collectors.mapping(ReviewerWhitelistRecord::getEmployeeNo, Collectors.toList())));
        return SUPPORTED_ROLE_ORDER.stream()
                .filter(employeeNosByRole::containsKey)
                .map(role -> new RoleEmployeeNosView(role, List.copyOf(employeeNosByRole.get(role))))
                .toList();
    }

    /** 按工号或姓名关键字分页返回当前启用白名单，供管理页面按主键精确删除。 */
    public ReviewerWhitelistPage listPage(ReviewerWhitelistQuery query, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        int pageNo = normalizePageNo(query.pageNo());
        int pageSize = normalizePageSize(query.pageSize());
        long total = whitelistMapper.countByKeyword(keyword);
        List<ReviewerWhitelistView> items = whitelistMapper.findPageByKeyword(keyword, (pageNo - 1) * pageSize, pageSize).stream()
                .map(record -> new ReviewerWhitelistView(record.getId(), ReviewerWhitelistRole.valueOf(record.getReviewRole()), record.getEmployeeNo(),
                        record.getDisplayName(), record.getEmail(), record.getMobile(), record.getDepartmentName(), record.getCreatedAt()))
                .toList();
        return new ReviewerWhitelistPage(total, pageNo, pageSize, items);
    }

    /**
     * 返回指定白名单职责下可被实际分配的人员。白名单自身即为人员目录，不再关联本地用户表。
     */
    public List<AssignableReviewerView> listAssignableUsers(List<ReviewerWhitelistRole> whitelistRoles) {
        if (whitelistRoles == null || whitelistRoles.isEmpty()) {
            return List.of();
        }
        List<String> roleCodes = whitelistRoles.stream().distinct().map(Enum::name).toList();
        return whitelistMapper.findAssignableUsersByRoles(roleCodes).stream()
                .map(AssignableReviewerView::from)
                .toList();
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_USER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无评审白名单维护权限");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null ? 1 : Math.max(pageNo, 1);
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 1000);
    }

    public record RoleEmployeeNos(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record RoleEmployeeNosView(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record ReviewerWhitelistView(Long id, ReviewerWhitelistRole reviewRole, String employeeNo, String displayName,
                                        String email, String mobile, String departmentName, java.time.LocalDateTime createdAt) { }
    public record ReviewerWhitelistQuery(String keyword, Integer pageNo, Integer pageSize) { }
    public record ReviewerWhitelistPage(long total, int pageNo, int pageSize, List<ReviewerWhitelistView> items) { }
    public record AssignableReviewerView(Long userId, String employeeNo, String displayName, String departmentName,
                                         ReviewerWhitelistRole whitelistRole) {
        static AssignableReviewerView from(AssignableReviewerRecord record) {
            return new AssignableReviewerView(record.getUserId(), record.getEmployeeNo(), record.getDisplayName(),
                    record.getDepartmentName(), ReviewerWhitelistRole.valueOf(record.getWhitelistRole()));
        }
    }
    public record SaveResult(int createdCount, List<RoleEmployeeNosView> roleEmployeeNos) { }
    public record DeleteResult(int deletedCount) { }
}
