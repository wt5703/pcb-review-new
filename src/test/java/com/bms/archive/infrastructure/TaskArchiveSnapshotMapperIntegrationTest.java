package com.bms.archive.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证归档快照在本地数据库中可按任务唯一写入并读取；流程节点不写入快照表。
 */
@SpringBootTest
class TaskArchiveSnapshotMapperIntegrationTest {
    @Autowired
    private TaskArchiveSnapshotMapper snapshotMapper;

    @Test
    void shouldPersistAndLoadArchiveSnapshot() {
        TaskArchiveSnapshotRecord snapshot = new TaskArchiveSnapshotRecord(9901L, "[]", "[]");
        snapshotMapper.insert(snapshot);

        assertThat(snapshotMapper.existsByTaskId(9901L)).isTrue();
        assertThat(snapshotMapper.findByTaskId(9901L).fileSnapshot()).isEqualTo("[]");
    }
}
