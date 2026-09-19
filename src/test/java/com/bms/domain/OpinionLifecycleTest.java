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
        ReviewOpinion opinion = ReviewOpinion.raise(1L, "PCB_EXPERT", "严重问题", 10L, 100L);

        opinion.reply(20L, ReplyType.ACCEPT, null, 101L);
        assertThat(opinion.status()).isEqualTo(OpinionStatus.PENDING_CONFIRMATION);

        opinion.confirm(10L, false, "请补充修改说明");
        assertThat(opinion.status()).isEqualTo(OpinionStatus.PENDING_REPLY);

        opinion.reply(20L, ReplyType.ACCEPT, null, 102L);
        opinion.confirm(10L, true, "通过");
        assertThat(opinion.status()).isEqualTo(OpinionStatus.CONFIRMED_PASS);
    }

    @Test
    void onlyRaiserMayConfirmOpinion() {
        ReviewOpinion opinion = ReviewOpinion.raise(1L, "PCB_EXPERT", "问题", 10L, 100L);
        opinion.reply(20L, ReplyType.ACCEPT, null, 101L);

        assertThatThrownBy(() -> opinion.confirm(11L, true, ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
