package com.bms.notification.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 复用部署环境已有的 {@code mail.*} 配置创建 JavaMailSender；没有 host 时不创建，自动回退本地 Mock。
 * 密码、端口、SSL/TLS 等可选参数仅在既有配置提供时写入，代码不补充或猜测默认值。
 */
@Configuration
@EnableConfigurationProperties(MailSenderConfiguration.ExistingMailProperties.class)
public class MailSenderConfiguration {
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    @ConditionalOnProperty(prefix = "mail", name = "host")
    JavaMailSender configuredMailSender(ExistingMailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            sender.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            sender.setPassword(properties.getPassword());
        }
        if (properties.getPort() != null) {
            sender.setPort(properties.getPort());
        }
        return sender;
    }

    @ConfigurationProperties(prefix = "mail")
    public static class ExistingMailProperties {
        private String host;
        private String username;
        private String password;
        private Integer port;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
    }
}
