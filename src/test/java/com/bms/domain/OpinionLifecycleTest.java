package com.bms.domain;

import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.ReviewOpinion;
import com.bms.review.domain.ReplyType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 评审意见闭环领域测试类。
 */


class OpinionLifecycleTest {

    @Test
    void opinionShouldFollowReplyConfirmAndRetryLifecycle() {
        ReviewOpinion opinion = ReviewOpinion.raise(1L, "PCB_EXPERT", "严重问题", "BMS010");

        opinion.reply("BMS020", ReplyType.ACCEPT, null);
        assertThat(opinion.status()).isEqualTo(OpinionStatus.PENDING_CONFIRMATION);

        opinion.confirm("BMS010", false, "请补充修改说明");
        assertThat(opinion.status()).isEqualTo(OpinionStatus.PENDING_REPLY);

        opinion.reply("BMS020", ReplyType.ACCEPT, null);
        opinion.confirm("BMS010", true, "通过");
        assertThat(opinion.status()).isEqualTo(OpinionStatus.CONFIRMED_PASS);
    }

    @Test
    void onlyRaiserMayConfirmOpinion() {
        ReviewOpinion opinion = ReviewOpinion.raise(1L, "PCB_EXPERT", "问题", "BMS010");
        opinion.reply("BMS020", ReplyType.ACCEPT, null);

        assertThatThrownBy(() -> opinion.confirm("BMS011", true, ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
