package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateMapper;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateRecord;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 管理本地阶段的互检检查项模板录入；模板维护权限暂复用互检管理权限，独立版本治理将在模板管理需求明确后扩展。
 */
@Service
public class CheckItemTemplateApplicationService {
    private final CheckItemTemplateMapper templateMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public CheckItemTemplateApplicationService(CheckItemTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    @Transactional
    public TemplateView create(CreateTemplateCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(templateMapper.nextId());
        record.setReviewType(command.reviewType().name());
        record.setItemKey(command.itemKey());
        record.setParentItemKey(command.parentItemKey());
        record.setItemName(command.itemName());
        record.setSortNo(command.sortNo());
        record.setEnabled(true);
        record.setVersion(0L);
        templateMapper.insert(record);
        return TemplateView.from(record);
    }

    @Transactional
    public TemplateView update(long templateId, UpdateTemplateCommand command, CurrentUser currentUser) {
        requireManagePermission(currentUser);
        CheckItemTemplateRecord record = templateMapper.findById(templateId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项模板不存在");
        }
        record.setItemKey(command.itemKey());
        record.setParentItemKey(command.parentItemKey());
        record.setItemName(command.itemName());
        record.setSortNo(command.sortNo());
        record.setEnabled(command.enabled());
        record.setVersion(command.version());
        if (templateMapper.update(record) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项模板已被其他操作更新，请刷新后重试");
        }
        record.setVersion(record.getVersion() + 1);
        return TemplateView.from(record);
    }

    private void requireManagePermission(CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), Permission.MANAGE_MUTUAL_CHECK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无检查项模板管理权限");
        }
    }

    public record CreateTemplateCommand(ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo) {
    }

    public record UpdateTemplateCommand(String itemKey, String parentItemKey, String itemName, int sortNo, boolean enabled, long version) {
    }

    public record TemplateView(Long id, ReviewType reviewType, String itemKey, String parentItemKey, String itemName, int sortNo) {
        static TemplateView from(CheckItemTemplateRecord record) {
            return new TemplateView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getItemKey(),
                    record.getParentItemKey(), record.getItemName(), record.getSortNo());
        }
    }
}
