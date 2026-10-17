package com.bms.review.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckItemTemplateRequestTest {
    @Test
    void updateRequestUsesIdForCategoryAndItems() throws Exception {
        var request = new ObjectMapper().readValue(
                "{\"id\":1,\"itemName\":\"大类\",\"items\":[{\"id\":2,\"itemName\":\"已有子项\"},{\"itemName\":\"新增子项\"}]}",
                CheckItemTemplateController.UpdateTemplateRequest.class);

        assertThat(request.id()).isEqualTo(1L);
        assertThat(request.items().get(0).id()).isEqualTo(2L);
        assertThat(request.items().get(1).id()).isNull();
    }
}
