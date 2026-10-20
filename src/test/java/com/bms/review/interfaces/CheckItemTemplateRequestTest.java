package com.bms.review.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckItemTemplateRequestTest {
    @Test
    void updateRequestUsesItemIdForCategoryAndItems() throws Exception {
        var request = new ObjectMapper().readValue(
                "{\"itemId\":1,\"itemName\":\"大类\",\"items\":[{\"itemId\":2,\"itemName\":\"已有子项\"},{\"itemName\":\"新增子项\"}],\"deletedItemIds\":[3,4]}",
                CheckItemTemplateController.UpdateTemplateRequest.class);

        assertThat(request.itemId()).isEqualTo(1L);
        assertThat(request.items().get(0).itemId()).isEqualTo(2L);
        assertThat(request.deletedItemIds()).containsExactly(3L, 4L);
        assertThat(request.items().get(1).itemId()).isNull();
    }
}
