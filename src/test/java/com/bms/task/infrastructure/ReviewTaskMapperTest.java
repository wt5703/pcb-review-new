package com.bms.task.infrastructure;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTaskMapperTest {
    @Test
    void taskPageShouldOrderByIdDescendingBeforePaging() throws Exception {
        Select select = ReviewTaskMapper.class.getMethod("findPage", String.class, String.class, List.class,
                Long.class, String.class, boolean.class, long.class, int.class).getAnnotation(Select.class);

        assertThat(String.join(" ", select.value()))
                .contains("ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}");
    }
}
