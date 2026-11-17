package com.bms.review.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@com.bms.TestDatabase
class ReviewerDepartmentIntegrationTest {
    @Autowired ReviewerWhitelistMapper mapper;

    @Test
    void selectsExactDepartmentWithoutRequiringRolesAndExcludesDeletedPeople() {
        var member = person("DEPT1", "BMS硬件开发部"); mapper.insert(member);
        var other = person("DEPT2", "其他BMS硬件开发部"); mapper.insert(other);
        var deleted = person("DEPT3", "BMS硬件开发部"); mapper.insert(deleted);
        mapper.logicDeleteById(deleted.getId(), "TEST");
        assertThat(mapper.findActiveByDepartmentName("BMS硬件开发部"))
                .extracting(ReviewerWhitelistRecord::getEmployeeNo).contains("DEPT1").doesNotContain("DEPT2", "DEPT3");
    }

    private ReviewerWhitelistRecord person(String employeeNo, String department) {
        var person = new ReviewerWhitelistRecord();
        person.setEmployeeNo(employeeNo); person.setDisplayName("测试人员"); person.setDepartmentName(department);
        person.setEmail(employeeNo + "@example.com"); person.setCreatedByEmployeeNo("TEST");
        return person;
    }
}
