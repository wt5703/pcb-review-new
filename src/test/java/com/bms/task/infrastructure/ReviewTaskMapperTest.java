package com.bms.task.infrastructure;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTaskMapperTest {
    @Test
    void taskPageShouldOrderByIdDescendingBeforePaging() throws Exception {
        Select select = ReviewTaskMapper.class.getMethod("findPage", String.class, String.class, String.class,
                String.class, List.class, Long.class, String.class, boolean.class, long.class, int.class)
                .getAnnotation(Select.class);

        assertThat(String.join(" ", select.value()))
                .contains("task_name LIKE CONCAT('%', #{taskName}, '%')",
                        "project_name LIKE CONCAT('%', #{projectName}, '%')",
                        "designer_employee_no=#{designerEmployeeNo}",
                        "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
                .doesNotContain("#{keyword}");
    }

    @Test
    void myTasksShouldOrderUnfinishedTasksByIdDescending() throws Exception {
        Select select = ReviewTaskMapper.class.getMethod("findAllUnfinished").getAnnotation(Select.class);

        assertThat(String.join(" ", select.value()))
                .contains("WHERE status <> 'FINISHED' ORDER BY id DESC");
    }
}
