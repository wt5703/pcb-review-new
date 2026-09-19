package com.bms.notification.application;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 抽象邮件发送边界，当前由本地 Mock 实现，后续接入公司邮件服务时保持通知应用服务和业务 Outbox 逻辑不变。
 */
public interface MailGateway {
    void send(String recipient, String templateCode, String payload);
}
