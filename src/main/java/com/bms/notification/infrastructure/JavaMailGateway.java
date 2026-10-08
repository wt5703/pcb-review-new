package com.bms.notification.infrastructure;

import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.notification.application.MailGateway;
import com.bms.notification.domain.MailMessage;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 使用项目配置的 JavaMailSender 投递 Outbox 邮件，并从文件资源服务读取已登记的附件。 */
@Component
@ConditionalOnProperty(prefix = "mail", name = "host")
public class JavaMailGateway implements MailGateway {
    private final JavaMailSender mailSender;
    private final ResourceServiceClient resourceServiceClient;

    public JavaMailGateway(JavaMailSender mailSender, ResourceServiceClient resourceServiceClient) {
        this.mailSender = mailSender;
        this.resourceServiceClient = resourceServiceClient;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            if (mailSender instanceof org.springframework.mail.javamail.JavaMailSenderImpl sender
                    && sender.getUsername() != null && !sender.getUsername().isBlank()) {
                helper.setFrom(sender.getUsername());
            }
            helper.setTo(addresses(message.recipients()));
            String[] carbonCopies = addresses(message.carbonCopies());
            if (carbonCopies.length > 0) {
                helper.setCc(carbonCopies);
            }
            helper.setSubject(message.subject());
            helper.setText(message.content(), false);
            for (MailMessage.MailAttachment attachment : message.attachments()) {
                byte[] content = resourceServiceClient.download(attachment.fileName(), attachment.resourcePath());
                helper.addAttachment(attachment.fileName(), new ByteArrayResource(content));
            }
            mailSender.send(mimeMessage);
        } catch (Exception exception) {
            throw new IllegalStateException("邮件投递失败：" + exception.getMessage(), exception);
        }
    }

    private String[] addresses(List<MailMessage.MailRecipient> recipients) {
        return recipients.stream().map(MailMessage.MailRecipient::email)
                .filter(email -> email != null && !email.isBlank()).distinct().toArray(String[]::new);
    }
}
