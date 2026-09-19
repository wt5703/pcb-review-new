package com.bms.notification.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventEntity;
import com.bms.notification.infrastructure.OutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证 Outbox 通知事件的原子认领、成功投递记录、失败记录与重试状态更新，确保邮件异常不会传播并破坏已提交的业务事务。
 */
class NotificationApplicationServiceTest {
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final NotificationSendRecordMapper sendRecordMapper = mock(NotificationSendRecordMapper.class);
    private final MailGateway mailGateway = mock(MailGateway.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final NotificationApplicationService service = new NotificationApplicationService(outboxEventMapper, sendRecordMapper,
            mailGateway, auditMapper);

    @Test
    void shouldMarkClaimedEventPublishedAfterMockMailSuccess() {
        OutboxEventEntity event = event(11L, "PCB_TASK_CREATED", "{}");
        when(outboxEventMapper.findDispatchable(20, 3)).thenReturn(List.of(event));
        when(outboxEventMapper.claimForDispatch(11L)).thenReturn(1);

        NotificationApplicationService.DispatchResult result = service.dispatch(20, 3);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        verify(outboxEventMapper).markPublished(11L);
        verify(sendRecordMapper).insert(any(NotificationSendRecord.class));
        verify(auditMapper).insert(any());
    }

    @Test
    void shouldRecordFailureAndLeaveEventRetryableWhenMailFails() {
        OutboxEventEntity event = event(12L, "PCB_TASK_FINISHED", "{}");
        when(outboxEventMapper.findDispatchable(20, 3)).thenReturn(List.of(event));
        when(outboxEventMapper.claimForDispatch(12L)).thenReturn(1);
        doThrow(new IllegalStateException("邮件服务不可用")).when(mailGateway).send("TBD", "PCB_TASK_FINISHED", "{}");

        NotificationApplicationService.DispatchResult result = service.dispatch(20, 3);

        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        verify(outboxEventMapper).markFailed(12L);
        verify(sendRecordMapper).insert(any(NotificationSendRecord.class));
    }

    private OutboxEventEntity event(long id, String eventType, String payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(id);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setRetryCount(0);
        event.setStatus("PENDING");
        return event;
    }
}
