package com.bms.notification.application;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

/** 从独立文本模板渲染邮件正文，业务服务只提供变量，不拼接完整邮件内容。 */
@Service
public class MailTemplateService {
    private final TemplateEngine templateEngine;

    public MailTemplateService() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode("TEXT");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    public String render(ReviewMailType type, Map<String, String> variables) {
        Context context = new Context(java.util.Locale.CHINA);
        variables.forEach(context::setVariable);
        return templateEngine.process(type.templateName(), context).trim();
    }
}
