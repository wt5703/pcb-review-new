package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
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
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 统一维护白名单人员及其多个评审角色，供人员选择、校验及目录解析使用。
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
    public SaveResult add(List<RoleEmployeeNos> roleEmployeeNos, String operatorEmployeeNo, String operatorName) {
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
                // 锁定人员，串行处理该人员的角色增删，避免并发删除最后一个角色时状态不一致。
                ReviewerWhitelistRecord person = whitelistMapper.lockByEmployeeNo(employeeNo);
                ReviewerWhitelistRecord existingRole = whitelistMapper.lockActiveRole(roleEmployeeNo.reviewRole().name(), employeeNo);
                if (person == null) {
                    person = new ReviewerWhitelistRecord();
                    person.setEmployeeNo(employeeNo);
                    person.setCreatedByEmployeeNo(operatorEmployeeNo);
                    // 人员资料只保存一份；新增时从用户中心取得，后续查询直接使用本地资料。
                    fillProfile(person);
                    whitelistMapper.insert(person);
                } else if (Boolean.TRUE.equals(person.getDeleted())) {
                    fillProfile(person);
                    whitelistMapper.restore(person);
                } else if (fillProfile(person)) {
                    whitelistMapper.updateProfile(person);
                }
                if (existingRole == null) {
                    whitelistMapper.addOrRestoreRole(employeeNo, roleEmployeeNo.reviewRole().name(), operatorEmployeeNo);
                    createdCount++;
                }
            }
        }
        return new SaveResult(createdCount, views());
    }

    @Transactional
    public DeleteResult remove(Long id, String employeeNo, String operatorEmployeeNo, String operatorName) {
        boolean hasId = id != null;
        boolean hasEmployeeNo = employeeNo != null && !employeeNo.isBlank();
        if (hasId == hasEmployeeNo) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除白名单时必须且只能传主键 id 或员工工号 employeeNo");
        }
        ReviewerWhitelistRecord person = hasId ? whitelistMapper.lockActiveById(id) : whitelistMapper.lockByEmployeeNo(employeeNo.trim());
        if (person == null || Boolean.TRUE.equals(person.getDeleted())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到可删除的白名单人员");
        }
        int deletedCount = whitelistMapper.logicDeleteById(person.getId(), operatorEmployeeNo);
        if (deletedCount == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到可删除的白名单记录");
        }
        whitelistMapper.logicDeleteRolesByEmployeeNo(person.getEmployeeNo(), operatorEmployeeNo);
        return new DeleteResult(deletedCount);
    }

    /** 删除一个人的指定角色；最后一个角色删除时同时停用人员记录。 */
    @Transactional
    public DeleteResult removeRole(long id, ReviewerWhitelistRole role, String operatorEmployeeNo, String operatorName) {
        ReviewerWhitelistRecord person = whitelistMapper.lockActiveById(id);
        if (person == null || role == null || whitelistMapper.logicDeleteRole(person.getEmployeeNo(), role.name(), operatorEmployeeNo) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到可删除的白名单角色");
        }
        if (whitelistMapper.countActiveRoles(person.getEmployeeNo()) == 0) {
            whitelistMapper.logicDeleteById(id, operatorEmployeeNo);
        }
        return new DeleteResult(1);
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
     * 按人员分页，再批量加载该页人员的全部角色，确保分页总数和页大小均按人员计算。
     * 人员资料已在新增白名单时固化，因此筛选、计数和展示不调用用户中心。
     */
    public ReviewerWhitelistPage listPage(ReviewerWhitelistQuery query, String operatorEmployeeNo, String operatorName) {
        ReviewerWhitelistQuery validQuery = query == null ? new ReviewerWhitelistQuery(null, null, null) : query;
        String keyword = validQuery.keyword() == null ? "" : validQuery.keyword().trim();
        int pageNum = normalizePageNum(validQuery.pageNum());
        int pageSize = normalizePageSize(validQuery.pageSize());
        long total = whitelistMapper.countByKeyword(keyword);
        long offset = (long) (pageNum - 1) * pageSize;
        List<ReviewerWhitelistRecord> people = total == 0 ? List.of() : whitelistMapper.findPageByKeyword(keyword, offset, pageSize);
        Map<String, List<ReviewerWhitelistRole>> rolesByEmployee = rolesByEmployee(people.stream().map(ReviewerWhitelistRecord::getEmployeeNo).toList());
        List<ReviewerWhitelistView> items = people.stream().map(person -> toView(person, rolesByEmployee.getOrDefault(person.getEmployeeNo(), List.of()))).toList();
        return new ReviewerWhitelistPage(total, pageNum, pageSize, items);
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
        Map<String, AssignableReviewerRecord> people = records.stream().collect(Collectors.toMap(
                AssignableReviewerRecord::getEmployeeNo, record -> record, (first, ignored) -> first, LinkedHashMap::new));
        Map<String, List<ReviewerWhitelistRole>> roles = rolesByEmployee(List.copyOf(people.keySet()));
        return people.values().stream().map(record -> AssignableReviewerView.from(record, roles.getOrDefault(record.getEmployeeNo(), List.of()))).toList();
    }

    public Map<ReviewerWhitelistRole, List<ReviewerWhitelistView>> listPeopleGroupedByRole() {
        List<ReviewerWhitelistRecord> records = whitelistMapper.findAll();
        Map<String, List<ReviewerWhitelistRole>> rolesByEmployee = records.stream().collect(Collectors.groupingBy(
                ReviewerWhitelistRecord::getEmployeeNo,
                Collectors.mapping(record -> ReviewerWhitelistRole.valueOf(record.getReviewRole()), Collectors.toList())));
        Map<ReviewerWhitelistRole, Map<String, ReviewerWhitelistRecord>> grouped = new LinkedHashMap<>();
        for (ReviewerWhitelistRecord record : records) {
            grouped.computeIfAbsent(ReviewerWhitelistRole.valueOf(record.getReviewRole()), key -> new LinkedHashMap<>())
                    .putIfAbsent(record.getEmployeeNo(), record);
        }
        Map<ReviewerWhitelistRole, List<ReviewerWhitelistView>> result = new LinkedHashMap<>();
        for (ReviewerWhitelistRole role : SUPPORTED_ROLE_ORDER) {
            Map<String, ReviewerWhitelistRecord> people = grouped.get(role);
            if (people == null) {
                continue;
            }
            result.put(role, people.values().stream()
                    .map(record -> toView(record, rolesByEmployee.getOrDefault(record.getEmployeeNo(), List.of())))
                    .toList());
        }
        return result;
    }

    private Map<String, List<ReviewerWhitelistRole>> rolesByEmployee(List<String> employeeNos) {
        if (employeeNos.isEmpty()) return Map.of();
        return whitelistMapper.findRolesByEmployeeNos(employeeNos).stream().collect(Collectors.groupingBy(
                ReviewerWhitelistRecord::getEmployeeNo,
                Collectors.mapping(record -> ReviewerWhitelistRole.valueOf(record.getReviewRole()), Collectors.toList())));
    }

    /** 白名单目录查询与维护共用同一应用服务，确保任务分配只读取本地已固化的人员资料。 */
    public WhitelistPersonView findByEmployeeNo(String employeeNo) {
        ReviewerWhitelistRecord record = whitelistMapper.findActiveByEmployeeNo(employeeNo);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到白名单人员工号：" + employeeNo);
        }
        return WhitelistPersonView.from(record);
    }

    /**
     * 任务展示只读取本地白名单中已固化的人员资料，不访问用户中心。
     * 设计者等非白名单人员没有本地资料时保留任务中已有姓名，最终才回退显示员工工号。
     */
    public String findDisplayNameOrDefault(String employeeNo, String defaultName) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return defaultName;
        }
        ReviewerWhitelistRecord record = whitelistMapper.findActiveByEmployeeNo(employeeNo);
        if (record != null && record.getDisplayName() != null && !record.getDisplayName().isBlank()) {
            return record.getDisplayName();
        }
        return defaultName == null || defaultName.isBlank() ? employeeNo : defaultName;
    }

    /** 批量读取白名单内已固化的姓名，供任务详情一次性补全评审人展示信息。 */
    public Map<String, String> findDisplayNamesByEmployeeNos(List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return Map.of();
        }
        List<String> distinctEmployeeNos = employeeNos.stream()
                .filter(employeeNo -> employeeNo != null && !employeeNo.isBlank()).distinct().toList();
        if (distinctEmployeeNos.isEmpty()) {
            return Map.of();
        }
        return whitelistMapper.findActiveByEmployeeNos(distinctEmployeeNos).stream()
                .collect(Collectors.toMap(ReviewerWhitelistRecord::getEmployeeNo,
                        record -> record.getDisplayName() == null || record.getDisplayName().isBlank()
                                ? record.getEmployeeNo() : record.getDisplayName()));
    }

    public List<WhitelistPersonView> findByEmployeeNos(List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(this::findByEmployeeNo).toList();
    }

    /** 暂不校验人员和角色是否属于白名单；已有人员资料仍用于返回展示信息。 */
    public List<WhitelistPersonView> findByRoleAndEmployeeNos(ReviewerWhitelistRole whitelistRole,
                                                               List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(employeeNo -> {
            ReviewerWhitelistRecord record = whitelistMapper.findActiveByEmployeeNo(employeeNo);
            return record == null ? new WhitelistPersonView(employeeNo, null, null, null, null)
                    : WhitelistPersonView.from(record);
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

    private ReviewerWhitelistView toView(ReviewerWhitelistRecord record, List<ReviewerWhitelistRole> roles) {
        return new ReviewerWhitelistView(record.getId(), List.copyOf(roles), record.getEmployeeNo(),
                record.getDisplayName(), record.getEmail(), record.getMobile(), record.getDepartmentName(), record.getCreatedAt());
    }

    private int normalizePageNum(Integer pageNum) {
        if (pageNum != null && pageNum < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单页码必须从 1 开始");
        }
        return pageNum == null ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize != null && (pageSize < 1 || pageSize > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "白名单每页条数必须在 1 到 1000 之间");
        }
        return pageSize == null ? 20 : pageSize;
    }

    public record RoleEmployeeNos(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record RoleEmployeeNosView(ReviewerWhitelistRole reviewRole, List<String> employeeNos) { }
    public record ReviewerWhitelistView(Long id, List<ReviewerWhitelistRole> reviewRoles, String employeeNo, String displayName,
                                        String email, String mobile, String departmentName, java.time.LocalDateTime createdAt) { }
    public record ReviewerWhitelistQuery(String keyword, Integer pageNum, Integer pageSize) { }
    /** 分页响应字段与前端约定为 pageNum、pageSize、total。 */
    public record ReviewerWhitelistPage(long total, int pageNum, int pageSize, List<ReviewerWhitelistView> items) { }
    public record AssignableReviewerView(String employeeNo, String displayName, String email, String mobile,
                                         String departmentName, List<ReviewerWhitelistRole> whitelistRoles,
                                         java.time.LocalDateTime createdAt) {
        static AssignableReviewerView from(AssignableReviewerRecord record, List<ReviewerWhitelistRole> roles) {
            return new AssignableReviewerView(record.getEmployeeNo(), record.getDisplayName(), record.getEmail(), record.getMobile(),
                    record.getDepartmentName(), List.copyOf(roles), record.getCreatedAt());
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
