package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
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
 * @description 统一维护和查询 PCB 评审可选人员白名单，以评审角色和员工工号建立一对多关系，供人员选择、校验及目录解析使用。
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
    private final UserCenterUserProfileClient userProfileClient;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public ReviewerWhitelistApplicationService(ReviewerWhitelistMapper whitelistMapper,
                                               UserCenterUserProfileClient userProfileClient) {
        this.whitelistMapper = whitelistMapper;
        this.userProfileClient = userProfileClient;
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
                ReviewerWhitelistRecord activeRecord = whitelistMapper.findByRoleAndEmployeeNo(roleEmployeeNo.reviewRole().name(), employeeNo);
                if (activeRecord != null) {
                    if (fillProfile(activeRecord)) {
                        whitelistMapper.updateProfile(activeRecord);
                    }
                    continue;
                }
                ReviewerWhitelistRecord historicalRecord = whitelistMapper.findAnyByRoleAndEmployeeNo(roleEmployeeNo.reviewRole().name(), employeeNo);
                if (historicalRecord != null) {
                    fillProfile(historicalRecord);
                    whitelistMapper.restore(historicalRecord);
                    createdCount++;
                    continue;
                }
                ReviewerWhitelistRecord record = new ReviewerWhitelistRecord();
                record.setReviewRole(roleEmployeeNo.reviewRole().name());
                record.setEmployeeNo(employeeNo);
                // 新增时从用户中心取一次资料并固化，后续白名单查询不再依赖外部接口。
                fillProfile(record);
                record.setCreatedByEmployeeNo(currentUser.employeeNo());
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
                ? whitelistMapper.logicDeleteById(id, currentUser.employeeNo())
                : whitelistMapper.logicDeleteByEmployeeNo(employeeNo.trim(), currentUser.employeeNo());
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

    /**
     * 按工号或姓名关键字分页返回当前启用白名单，供管理页面按主键精确删除。
     * 人员资料已在新增白名单时固化，因此筛选、计数和展示不调用用户中心。
     */
    public ReviewerWhitelistPage listPage(ReviewerWhitelistQuery query, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        ReviewerWhitelistQuery validQuery = query == null ? new ReviewerWhitelistQuery(null, null, null) : query;
        String keyword = validQuery.keyword() == null ? "" : validQuery.keyword().trim();
        int pageNo = normalizePageNo(validQuery.pageNo());
        int pageSize = normalizePageSize(validQuery.pageSize());
        List<ReviewerWhitelistRecord> records = whitelistMapper.findAll();
        List<ReviewerWhitelistView> matched = records.stream()
                .map(this::toView)
                .filter(view -> matchesKeyword(view, keyword))
                .toList();
        long total = matched.size();
        int fromIndex = (int) Math.min((long) (pageNo - 1) * pageSize, matched.size());
        int toIndex = Math.min(fromIndex + pageSize, matched.size());
        List<ReviewerWhitelistView> items = matched.subList(fromIndex, toIndex);
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
        List<AssignableReviewerRecord> records = whitelistMapper.findAssignableUsersByRoles(roleCodes);
        return records.stream()
                .map(AssignableReviewerView::from)
                .toList();
    }

    /** 白名单目录查询与维护共用同一应用服务，确保任务分配只读取本地已固化的人员资料。 */
    public WhitelistPersonView findByEmployeeNo(String employeeNo) {
        ReviewerWhitelistRecord record = whitelistMapper.findActiveByEmployeeNo(employeeNo);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到白名单人员工号：" + employeeNo);
        }
        return WhitelistPersonView.from(record);
    }

    public List<WhitelistPersonView> findByEmployeeNos(List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(this::findByEmployeeNo).toList();
    }

    /** 按角色校验任务实际分配人员，不能只按工号命中任意一条白名单记录。 */
    public List<WhitelistPersonView> findByRoleAndEmployeeNos(ReviewerWhitelistRole whitelistRole,
                                                               List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(employeeNo -> {
            ReviewerWhitelistRecord record = whitelistMapper.findByRoleAndEmployeeNo(whitelistRole.name(), employeeNo);
            if (record == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "人员 " + employeeNo + " 不在" + whitelistRole.displayName() + "白名单中");
            }
            return WhitelistPersonView.from(record);
        }).toList();
    }

    /**
     * 用户中心成功返回至少一项资料时才覆盖已有缓存，避免临时网络故障清空已保存的邮箱、手机号等数据。
     */
    private boolean fillProfile(ReviewerWhitelistRecord record) {
        UserCenterUserProfileClient.UserProfile profile = userProfileClient.getByEmployeeNo(record.getEmployeeNo());
        boolean profileAvailable = !record.getEmployeeNo().equals(profile.displayName())
                || profile.email() != null || profile.mobile() != null || profile.departmentName() != null;
        if (!profileAvailable) {
            if (record.getId() == null) {
                record.setDisplayName(record.getEmployeeNo());
            }
            return false;
        }
        record.setDisplayName(profile.displayName());
        record.setEmail(profile.email());
        record.setMobile(profile.mobile());
        record.setDepartmentName(profile.departmentName());
        return true;
    }

    private ReviewerWhitelistView toView(ReviewerWhitelistRecord record) {
        return new ReviewerWhitelistView(record.getId(), ReviewerWhitelistRole.valueOf(record.getReviewRole()), record.getEmployeeNo(),
                record.getDisplayName(), record.getEmail(), record.getMobile(), record.getDepartmentName(), record.getCreatedAt());
    }

    private boolean matchesKeyword(ReviewerWhitelistView view, String keyword) {
        return keyword.isBlank() || view.employeeNo().contains(keyword)
                || (view.displayName() != null && view.displayName().contains(keyword));
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_USER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无评审白名单维护权限");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo != null && pageNo < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单页码必须从 1 开始");
        }
        return pageNo == null ? 1 : pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize != null && (pageSize < 1 || pageSize > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单每页条数必须在 1 到 1000 之间");
        }
        return pageSize == null ? 20 : pageSize;
    }

    public record RoleEmployeeNos(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record RoleEmployeeNosView(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record ReviewerWhitelistView(Long id, ReviewerWhitelistRole reviewRole, String employeeNo, String displayName,
                                        String email, String mobile, String departmentName, java.time.LocalDateTime createdAt) { }
    public record ReviewerWhitelistQuery(String keyword, Integer pageNo, Integer pageSize) { }
    public record ReviewerWhitelistPage(long total, int pageNo, int pageSize, List<ReviewerWhitelistView> items) { }
    public record AssignableReviewerView(String employeeNo, String displayName, String email, String mobile,
                                         String departmentName, ReviewerWhitelistRole whitelistRole,
                                         java.time.LocalDateTime createdAt) {
        static AssignableReviewerView from(AssignableReviewerRecord record) {
            return new AssignableReviewerView(record.getEmployeeNo(), record.getDisplayName(), record.getEmail(), record.getMobile(),
                    record.getDepartmentName(), ReviewerWhitelistRole.valueOf(record.getWhitelistRole()), record.getCreatedAt());
        }
    }
    public record WhitelistPersonView(String employeeNo, String displayName, String email, String mobile,
                                      String departmentName) {
        static WhitelistPersonView from(ReviewerWhitelistRecord record) {
            return new WhitelistPersonView(record.getEmployeeNo(), record.getDisplayName(), record.getEmail(),
                    record.getMobile(), record.getDepartmentName());
        }
    }
    public record SaveResult(int createdCount, List<RoleEmployeeNosView> roleEmployeeNos) { }
    public record DeleteResult(int deletedCount) { }
}
