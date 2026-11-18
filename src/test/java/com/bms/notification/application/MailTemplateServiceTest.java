package com.bms.notification.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用实际环境配置验证邮件链接，并确保模板缓存不会复用上一任务的 ID。 */
class MailTemplateServiceTest {
    @ParameterizedTest
    @CsvSource({
            "application-test.yml, http://10.195.135.149:31792",
            "application.yml, https://pcb-leapenergy.leapmotor.com"
    })
    void shouldUseTaskDetailsForFinishedMailAndTodoForOtherMail(String configFile, String baseUrl) throws IOException {
        var properties = new YamlPropertySourceLoader().load("mail-test", new ClassPathResource(configFile));
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplateService.class)
                .withInitializer(context -> properties.forEach(propertySource -> context.getEnvironment().getPropertySources().addFirst(propertySource)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var templates = context.getBean(MailTemplateService.class);
                    for (ReviewMailType type : ReviewMailType.values()) {
                        for (String taskId : new String[]{"12345", "67890"}) {
                            String content = templates.render(type, Map.of("taskId", taskId, "recipientNames", "张工",
                                    "greeting", "您好", "projectName", "BMS", "reviewName", "工艺评审"));
                            if (type == ReviewMailType.PCB_TASK_FINISHED || type == ReviewMailType.SCHEMATIC_TASK_FINISHED) {
                                assertThat(content).contains("请点击以下链接查看任务详情：")
                                        .endsWith(baseUrl + "/task/detail?id=" + taskId)
                                        .doesNotContain("myTodo", "我的待办", "[[", "${");
                            } else {
                                assertThat(content).contains(baseUrl + "/myTodo").doesNotContain("/task/detail");
                            }
                        }
                    }
                });
    }
}
