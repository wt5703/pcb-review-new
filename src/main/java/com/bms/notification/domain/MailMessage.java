package com.bms.notification.domain;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 表示一封待投递的业务邮件。标题、收件人、抄送、正文和附件均随 Outbox 事件持久化，业务事务提交后再由邮件网关投递。
 */
public record MailMessage(String notificationType, String subject, String content,
                          List<MailRecipient> recipients, List<MailRecipient> carbonCopies,
                          List<MailAttachment> attachments) {
    public MailMessage {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        carbonCopies = carbonCopies == null ? List.of() : List.copyOf(carbonCopies);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record MailRecipient(String name, String email) { }

    public record MailAttachment(String fileId, String fileName, String fileCategory, String resourcePath) { }
}
