package com.bms.review.application;

import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import com.bms.review.domain.ReviewerWhitelistRole;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 白名单人员目录。PCB 系统仅从 reviewer_whitelist 读取可分配人员资料，
 * 不维护独立用户账号或用户角色数据。
 */
@Service
public class ReviewerWhitelistDirectoryApplicationService {
    private final ReviewerWhitelistMapper whitelistMapper;

    public ReviewerWhitelistDirectoryApplicationService(ReviewerWhitelistMapper whitelistMapper) {
        this.whitelistMapper = whitelistMapper;
    }

    public WhitelistPersonView findByEmployeeNo(String employeeNo) {
        ReviewerWhitelistRecord record = whitelistMapper.findActiveByEmployeeNo(employeeNo);
        if (record == null) {
            throw new IllegalArgumentException("未找到白名单人员工号：" + employeeNo);
        }
        return from(record);
    }

    public List<WhitelistPersonView> findByEmployeeNos(List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(this::findByEmployeeNo).toList();
    }

    /**
     * 按白名单类别解析人员。一个人可同时在多个类别中，任务分配必须保留
     * 当前选择的类别，不能仅按工号取任意一条白名单记录。
     */
    public List<WhitelistPersonView> findByRoleAndEmployeeNos(ReviewerWhitelistRole whitelistRole,
                                                               List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return List.of();
        }
        return employeeNos.stream().distinct().map(employeeNo -> {
            ReviewerWhitelistRecord record = whitelistMapper.findByRoleAndEmployeeNo(whitelistRole.name(), employeeNo);
            if (record == null) {
                throw new IllegalArgumentException("人员 " + employeeNo + " 不在" + whitelistRole.displayName() + "白名单中");
            }
            return from(record);
        }).toList();
    }

    private WhitelistPersonView from(ReviewerWhitelistRecord record) {
        return new WhitelistPersonView(record.getId(), record.getEmployeeNo(), record.getDisplayName(), record.getEmail(),
                record.getMobile(), record.getDepartmentName());
    }

    public record WhitelistPersonView(Long id, String employeeNo, String displayName, String email, String mobile,
                                      String departmentName) { }
}
