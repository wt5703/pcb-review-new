package com.bms.identity.application;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerIdentityHeadersTest {
    @Test
    void controllerMethodsRequireOnlyIdentityHeadersUsedByTheirBusiness() throws Exception {
        Set<String> noIdentity = Set.of(
                "DictionaryController.taskOptions", "WorkflowController.listAssignableReviewers",
                "WorkflowController.listMutualCheckReviewers", "ReviewerWhitelistController.list",
                "ReviewerWhitelistController.people", "CheckItemTemplateController.checkExistence",
                "CheckItemTemplateController.importWorkbook", "CheckItemTemplateController.get",
                "CheckItemTemplateController.create", "CheckItemTemplateController.createItem",
                "CheckItemTemplateController.update",
                "CheckItemTemplateController.disable");
        Set<String> bothIdentityHeaders = Set.of(
                "CurrentUserController.currentUser", "TaskController.saveTaskDraft", "TaskController.submitTask",
                "OpinionController.raise", "OpinionController.submitNoOpinion",
                "CheckItemController.submit", "CheckItemController.submitBatch");
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = scanner.findCandidateComponents("com.bms");
        assertThat(controllers).isNotEmpty();
        for (var definition : controllers) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (var method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                Map<String, RequestHeader> headers = Arrays.stream(method.getParameters())
                        .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toMap(header -> header.value().isEmpty() ? header.name() : header.value(), header -> header));
                String methodName = controller.getSimpleName() + "." + method.getName();
                Set<String> expected = noIdentity.contains(methodName) ? Set.of()
                        : bothIdentityHeaders.contains(methodName) ? Set.of("userId", "userName") : Set.of("userId");
                assertThat(headers.keySet()).as(method.toString()).isEqualTo(expected);
                assertThat(headers.values()).as(method.toString()).allMatch(RequestHeader::required);
            }
        }
    }

}
