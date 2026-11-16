package com.bms.task.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StageDurationTest {
    @ParameterizedTest
    @CsvSource({"0,0秒", "55,55秒", "60,60秒", "61,1分1秒", "3579,59分39秒",
            "3600,60分", "3601,1小时1秒", "86344,23小时59分4秒", "86400,24小时",
            "86401,1天", "6768125,78天8小时2分钟"})
    void formatsDuration(long seconds, String expected) {
        assertThat(TaskApplicationService.formatStageDuration(seconds)).isEqualTo(expected);
    }
    @Test
    void preservesUnknownDuration() {
        assertThat(TaskApplicationService.formatStageDuration(null)).isNull();
    }
}
