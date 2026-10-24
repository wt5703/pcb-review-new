package com.bms.notification.infrastructure;

import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.notification.domain.MailMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证资源服务未返回文件内容时，邮件不会以空附件形式投递。 */
class JavaMailGatewayTest {

    @Test
    void shouldRejectEmptyAttachmentContent() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ResourceServiceClient resourceServiceClient = mock(ResourceServiceClient.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        when(resourceServiceClient.download("PCB.zip", "/resource/pcb")).thenReturn(new byte[0]);
        JavaMailGateway gateway = new JavaMailGateway(mailSender, resourceServiceClient);
        MailMessage message = new MailMessage("PCB_TASK_CREATED", "PCB 评审", "请评审",
                List.of(new MailMessage.MailRecipient("张工", "zhang@example.com")), List.of(),
                List.of(new MailMessage.MailAttachment("file-1", "PCB.zip", "PCB_REVIEW", "/resource/pcb")));

        assertThatThrownBy(() -> gateway.send(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("邮件附件内容为空：PCB.zip");
    }
}
