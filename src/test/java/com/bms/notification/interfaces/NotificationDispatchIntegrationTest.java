package com.bms.notification.interfaces;

import com.bms.notification.application.OutboxEventPublisher;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventEntity;
import com.bms.notification.infrastructure.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证已提交业务 Outbox 事件可经受控 REST 接口被本地邮件 Mock 消费，并落下已发布状态与可追溯发送记录。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationDispatchIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
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

        mockMvc.perform(post("/notifications/dispatch")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100,\"maxRetries\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").exists());

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
