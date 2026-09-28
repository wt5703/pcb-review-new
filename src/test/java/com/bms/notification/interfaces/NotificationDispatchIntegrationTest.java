package com.bms.notification.interfaces;

import com.bms.notification.application.NotificationApplicationService;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.notification.domain.MailMessage;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventEntity;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private NotificationSendRecordMapper notificationSendRecordMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldDispatchPublishedTaskEventAndPersistSendRecord() throws Exception {
        long taskId = 9701L;
        outboxEventMapper.insert(new OutboxEventRecord(ReviewMailNotificationApplicationService.REVIEW_MAIL_EVENT, "REVIEW_TASK", taskId,
                objectMapper.writeValueAsString(new MailMessage("PCB_TASK_CREATED", "测试标题", "测试正文",
                        java.util.List.of(new MailMessage.MailRecipient("王工", "wang@bms.example.com")), java.util.List.of(), java.util.List.of())), "PENDING"));
        OutboxEventEntity event = outboxEventMapper.findByAggregate("REVIEW_TASK", taskId).get(0);

        NotificationApplicationService.DispatchResult result = notificationApplicationService.dispatch(100, 3);
        assertThat(result.successCount()).isPositive();

        OutboxEventEntity published = outboxEventMapper.findByAggregate("REVIEW_TASK", taskId).get(0);
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(notificationSendRecordMapper.findByOutboxEventId(event.getId()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.recipient()).isEqualTo("wang@bms.example.com");
                    assertThat(record.deliveryStatus()).isEqualTo("SUCCESS");
                });
    }
}
