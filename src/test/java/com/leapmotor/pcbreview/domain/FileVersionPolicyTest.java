package com.leapmotor.pcbreview.domain;

import com.leapmotor.pcbreview.file.domain.FileVersionPolicy;
import com.leapmotor.pcbreview.file.domain.UploadDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 文件版本策略领域测试类。
 */


class FileVersionPolicyTest {

    private final FileVersionPolicy policy = new FileVersionPolicy();

    @Test
    void sameMd5AsLatestMustNotCreateAnotherVersion() {
        assertThat(policy.decide("abc", "abc", 3)).isEqualTo(UploadDecision.duplicate(3));
    }

    @Test
    void differentMd5CreatesNextVersion() {
        assertThat(policy.decide("abc", "def", 3)).isEqualTo(UploadDecision.newVersion(4));
    }
}
