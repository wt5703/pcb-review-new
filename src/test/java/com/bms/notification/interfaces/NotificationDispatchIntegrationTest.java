package com.bms.notification.interfaces;

import com.bms.notification.application.NotificationApplicationService;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventEntity;
import com.bms.notification.infrastructure.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证已提交业务 Outbox 事件由通知应用服务内部消费，并落下已发布状态与可追溯发送记录。
 */
@SpringBootTest
class NotificationDispatchIntegrationTest {
    @Autowired
    private NotificationApplicationService notificationApplicationService;
    @Autowired
    private OutboxEventPublisher outboxEventPublisher;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private NotificationSendRecordMapper notificationSendRecordMapper;

    @Test
    void shouldDispatchPublishedTaskEventAndPersistSendRecord() throws Exception {
        long taskId = 9701L;
        outboxEventPublisher.publishTaskEvent("TASK_STATUS_CHANGED", taskId, 1L);
        OutboxEventEntity event = outboxEventMapper.findByAggregate("REVIEW_TASK", taskId).get(0);

        NotificationApplicationService.DispatchResult result = notificationApplicationService.dispatch(100, 3);
        assertThat(result.successCount()).isPositive();

        OutboxEventEntity published = outboxEventMapper.findByAggregate("REVIEW_TASK", taskId).get(0);
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(notificationSendRecordMapper.findByOutboxEventId(event.getId()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.recipient()).isEqualTo("TBD");
                    assertThat(record.deliveryStatus()).isEqualTo("SUCCESS");
                });
    }
}
