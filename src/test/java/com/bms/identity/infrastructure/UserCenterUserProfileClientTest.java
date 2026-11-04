package com.bms.identity.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserCenterUserProfileClientTest {

    @Test
    void shouldExtractOnlyPcbApplicationRoleCodes() {
        List<String> roles = UserCenterUserProfileClient.extractPcbRoleCodes(List.of(
                new UserCenterUserProfileClient.UserCenterRoleBinding(
                        new UserCenterUserProfileClient.UserCenterApplication("LEAPENERGY_CLOUD"),
                        List.of(new UserCenterUserProfileClient.UserCenterRole("PLAIN-USER"))),
                new UserCenterUserProfileClient.UserCenterRoleBinding(
                        new UserCenterUserProfileClient.UserCenterApplication("LEAPENERGY_PCB"),
                        List.of(new UserCenterUserProfileClient.UserCenterRole("PCB-LEADER"),
                                new UserCenterUserProfileClient.UserCenterRole("PLAIN-USER"),
                                new UserCenterUserProfileClient.UserCenterRole("ADMIN"))),
                new UserCenterUserProfileClient.UserCenterRoleBinding(
                        new UserCenterUserProfileClient.UserCenterApplication("LEAPENERGY_PCB"),
                        List.of(new UserCenterUserProfileClient.UserCenterRole("ADMIN")))));

        assertThat(roles).containsExactly("PCB-LEADER", "PLAIN-USER", "ADMIN");
    }
}
