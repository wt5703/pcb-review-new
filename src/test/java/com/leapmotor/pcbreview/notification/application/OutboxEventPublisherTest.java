package com.leapmotor.pcbreview.notification.application;

import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证领域服务发布的通知事件会以待投递状态写入 Outbox，并携带任务和操作人定位信息，而不直接触发邮件发送。
 */
class OutboxEventPublisherTest {
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final OutboxEventPublisher publisher = new OutboxEventPublisher(outboxEventMapper);

    @Test
    void shouldPersistPendingTaskEventWithTraceablePayload() {
        publisher.publishTaskEvent("OPINION_REPLIED", 1001L, 10L);

        var captor = forClass(OutboxEventRecord.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEventRecord event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("OPINION_REPLIED");
        assertThat(event.aggregateType()).isEqualTo("REVIEW_TASK");
        assertThat(event.aggregateId()).isEqualTo(1001L);
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.payload()).contains("\"operatorId\":10");
    }
}
