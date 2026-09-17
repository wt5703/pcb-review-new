package com.leapmotor.pcbreview.notification.application;

import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditRecord;
import com.leapmotor.pcbreview.notification.domain.NotificationDeliveryStatus;
import com.leapmotor.pcbreview.notification.infrastructure.NotificationSendRecord;
import com.leapmotor.pcbreview.notification.infrastructure.NotificationSendRecordMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventEntity;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 消费业务 Outbox 事件并通过邮件 Mock 生成可查询投递记录，以原子认领、状态更新和失败重试避免重复成功发送影响业务数据。
 */
@Service
public class NotificationApplicationService {
    private static final String RECIPIENT_TBD = "TBD";
    private final OutboxEventMapper outboxEventMapper;
    private final NotificationSendRecordMapper sendRecordMapper;
    private final MailGateway mailGateway;
    private final OperationAuditMapper auditMapper;

    public NotificationApplicationService(OutboxEventMapper outboxEventMapper, NotificationSendRecordMapper sendRecordMapper,
                                          MailGateway mailGateway, OperationAuditMapper auditMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.sendRecordMapper = sendRecordMapper;
        this.mailGateway = mailGateway;
        this.auditMapper = auditMapper;
    }

    @Transactional
    public DispatchResult dispatch(int limit, int maxRetries) {
        int success = 0;
        int failed = 0;
        for (OutboxEventEntity event : outboxEventMapper.findDispatchable(normalizeLimit(limit), normalizeRetries(maxRetries))) {
            if (dispatchOne(event)) {
                success++;
            } else {
                failed++;
            }
        }
        return new DispatchResult(success, failed);
    }

    private boolean dispatchOne(OutboxEventEntity event) {
        if (outboxEventMapper.claimForDispatch(event.getId()) != 1) {
            return true;
        }
        try {
            mailGateway.send(RECIPIENT_TBD, event.getEventType(), event.getPayload());
            sendRecordMapper.insert(new NotificationSendRecord(event.getId(), event.getEventType(), RECIPIENT_TBD, event.getEventType(),
                    NotificationDeliveryStatus.SUCCESS.name(), null));
            outboxEventMapper.markPublished(event.getId());
            auditMapper.insert(new OperationAuditRecord("OUTBOX_EVENT", event.getId(), "NOTIFICATION_SENT", 0L, event.getEventType()));
            return true;
        } catch (RuntimeException exception) {
            sendRecordMapper.insert(new NotificationSendRecord(event.getId(), event.getEventType(), RECIPIENT_TBD, event.getEventType(),
                    NotificationDeliveryStatus.FAILED.name(), exception.getMessage()));
            outboxEventMapper.markFailed(event.getId());
            auditMapper.insert(new OperationAuditRecord("OUTBOX_EVENT", event.getId(), "NOTIFICATION_FAILED", 0L, event.getEventType()));
            return false;
        }
    }

    private int normalizeLimit(int limit) { return limit < 1 ? 20 : Math.min(limit, 100); }
    private int normalizeRetries(int maxRetries) { return maxRetries < 1 ? 3 : Math.min(maxRetries, 10); }

    public record DispatchResult(int successCount, int failedCount) {
    }
}
