package com.bms.notification.application;

import com.bms.notification.domain.NotificationDeliveryStatus;
import com.bms.notification.domain.MailMessage;
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventEntity;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 消费业务 Outbox 事件并通过邮件 Mock 生成可查询投递记录，以原子认领、状态更新和失败重试避免重复成功发送影响业务数据。
 */
@Service
public class NotificationApplicationService {
    private final OutboxEventMapper outboxEventMapper;
    private final NotificationSendRecordMapper sendRecordMapper;
    private final MailGateway mailGateway;
    private final ObjectMapper objectMapper;

    public NotificationApplicationService(OutboxEventMapper outboxEventMapper, NotificationSendRecordMapper sendRecordMapper,
                                          MailGateway mailGateway, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.sendRecordMapper = sendRecordMapper;
        this.mailGateway = mailGateway;
        this.objectMapper = objectMapper;
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
        // 只有明确组装完成的 REVIEW_MAIL 事件才调用邮件网关。任务状态、意见等普通领域事件
        // 仍可保留在 Outbox 供其他订阅方使用，但不能被误投递成一封通用邮件。
        if (!ReviewMailNotificationApplicationService.REVIEW_MAIL_EVENT.equals(event.getEventType())) {
            outboxEventMapper.markPublished(event.getId());
            return true;
        }
        try {
            MailMessage message = parseMail(event.getPayload());
            mailGateway.send(message);
            sendRecordMapper.insert(new NotificationSendRecord(event.getId(), message.notificationType(), recipients(message), message.subject(),
                    NotificationDeliveryStatus.SUCCESS.name(), null, null));
            outboxEventMapper.markPublished(event.getId());
            return true;
        } catch (RuntimeException exception) {
            sendRecordMapper.insert(new NotificationSendRecord(event.getId(), event.getEventType(), "", event.getEventType(),
                    NotificationDeliveryStatus.FAILED.name(), exception.getMessage(), null));
            outboxEventMapper.markFailed(event.getId());
            return false;
        }
    }

    /** 本地环境每五秒消费一次已提交的邮件 Outbox；生产可通过配置调整频率或替换为消息队列消费者。 */
    @Scheduled(fixedDelayString = "${notification.dispatch.fixed-delay-ms:5000}")
    @Transactional
    public void dispatchPending() {
        dispatch(20, 3);
    }

    private MailMessage parseMail(String payload) {
        try {
            return objectMapper.readValue(payload, MailMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("邮件通知载荷格式错误", exception);
        }
    }

    private String recipients(MailMessage message) {
        return message.recipients().stream().map(MailMessage.MailRecipient::email)
                .filter(email -> email != null && !email.isBlank()).collect(java.util.stream.Collectors.joining(","));
    }

    private int normalizeLimit(int limit) { return limit < 1 ? 20 : Math.min(limit, 100); }
    private int normalizeRetries(int maxRetries) { return maxRetries < 1 ? 3 : Math.min(maxRetries, 10); }

    public record DispatchResult(int successCount, int failedCount) {
    }
}
