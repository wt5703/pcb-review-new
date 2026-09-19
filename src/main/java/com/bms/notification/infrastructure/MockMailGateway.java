package com.bms.notification.infrastructure;

import com.bms.notification.application.MailGateway;
import org.springframework.stereotype.Component;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 本地阶段的邮件发送 Mock；载荷包含 forceMailFailure 标记时模拟外部邮件故障，用于验证失败记录与重试而不发送真实邮件。
 */
@Component
public class MockMailGateway implements MailGateway {
    @Override
    public void send(String recipient, String templateCode, String payload) {
        if (payload != null && payload.contains("forceMailFailure")) {
            throw new IllegalStateException("本地邮件 Mock 模拟失败");
        }
    }
}
