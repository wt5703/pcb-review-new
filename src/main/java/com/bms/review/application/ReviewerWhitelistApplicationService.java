package com.bms.review.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.review.domain.ReviewRole;
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
    private static final List<ReviewRole> SUPPORTED_ROLE_ORDER = List.of(
            ReviewRole.HARDWARE_EXPERT,
            ReviewRole.EMC_EXPERT,
            ReviewRole.STRUCTURE_EXPERT,
            ReviewRole.PROCESS_EXPERT,
            ReviewRole.PCB_EXPERT);
    private static final Set<ReviewRole> SUPPORTED_ROLES = Set.copyOf(SUPPORTED_ROLE_ORDER);

    private final ReviewerWhitelistMapper whitelistMapper;
    private final OperationAuditMapper auditMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public ReviewerWhitelistApplicationService(ReviewerWhitelistMapper whitelistMapper, OperationAuditMapper auditMapper) {
        this.whitelistMapper = whitelistMapper;
        this.auditMapper = auditMapper;
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
        Set<ReviewRole> duplicateRoles = roleEmployeeNos.stream().map(RoleEmployeeNos::reviewRole).collect(Collectors.toSet());
        if (duplicateRoles.size() != roleEmployeeNos.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一评审角色只能提交一次");
        }
        int createdCount = 0;
        for (RoleEmployeeNos roleEmployeeNo : roleEmployeeNos) {
            if (!SUPPORTED_ROLES.contains(roleEmployeeNo.reviewRole())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单仅支持硬件、EMC、结构、工艺和 PCB 评审角色");
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
                record.setCreatedBy(currentUser.id());
                whitelistMapper.insert(record);
                createdCount++;
            }
        }
        auditMapper.insert(new OperationAuditRecord("REVIEWER_WHITELIST", 0L, "REVIEWER_WHITELIST_ADDED", currentUser.id(),
                "新增映射数=" + createdCount));
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
        auditMapper.insert(new OperationAuditRecord("REVIEWER_WHITELIST", hasId ? id : 0L, "REVIEWER_WHITELIST_DELETED",
                currentUser.id(), hasId ? "按主键逻辑删除" : "按员工工号逻辑删除：" + employeeNo.trim()));
        return new DeleteResult(deletedCount);
    }

    public List<RoleEmployeeNosView> views() {
        Map<ReviewRole, List<String>> employeeNosByRole = whitelistMapper.findAll().stream()
                .collect(Collectors.groupingBy(record -> ReviewRole.valueOf(record.getReviewRole()),
                        Collectors.mapping(ReviewerWhitelistRecord::getEmployeeNo, Collectors.toList())));
        return SUPPORTED_ROLE_ORDER.stream()
                .filter(employeeNosByRole::containsKey)
                .map(role -> new RoleEmployeeNosView(role, List.copyOf(employeeNosByRole.get(role))))
                .toList();
    }

    /**
     * 返回指定白名单职责下可被实际分配的用户。白名单工号必须能解析到启用的用户账号，
     * 否则不会出现在人员选择器中，避免前端拿到无法用于 reviewerIds 的数据。
     */
    public List<AssignableReviewerView> listAssignableUsers(List<ReviewRole> whitelistRoles) {
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

    public record RoleEmployeeNos(ReviewRole reviewRole, List<String> employeeNos) { }
    public record RoleEmployeeNosView(ReviewRole reviewRole, List<String> employeeNos) { }
    public record AssignableReviewerView(Long userId, String employeeNo, String displayName, String departmentName,
                                         ReviewRole whitelistRole) {
        static AssignableReviewerView from(AssignableReviewerRecord record) {
            return new AssignableReviewerView(record.getUserId(), record.getEmployeeNo(), record.getDisplayName(),
                    record.getDepartmentName(), ReviewRole.valueOf(record.getWhitelistRole()));
        }
    }
    public record SaveResult(int createdCount, List<RoleEmployeeNosView> roleEmployeeNos) { }
    public record DeleteResult(int deletedCount) { }
}
