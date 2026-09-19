package com.bms.task.infrastructure;

import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 评审任务数据访问层集成测试类。
 */
@SpringBootTest
class ReviewTaskMapperIntegrationTest {
    @Autowired
    private ReviewTaskMapper mapper;

    @Test
    void taskShouldPersistAndLoadById() {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(9001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("BMS PCB评审");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(TaskStatus.DRAFT.name());
        task.setInitialFileIds("100");
        task.setVersion(0L);

        mapper.insert(task);

        assertThat(mapper.findById(9001L)).extracting(ReviewTaskRecord::getTaskName).isEqualTo("BMS PCB评审");
    }
}
