package com.bms.notification.application;

import com.bms.notification.domain.MailMessage;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 抽象邮件发送边界，当前由本地 Mock 实现，后续接入公司邮件服务时保持通知应用服务和业务 Outbox 逻辑不变。
 */
public interface MailGateway {
    /**
     * 投递一封已组装完成的业务邮件。生产实现可据此映射公司邮件 API；本地实现只做 Mock，
     * 不会向真实地址发送邮件。
     */
    void send(MailMessage message);
}
