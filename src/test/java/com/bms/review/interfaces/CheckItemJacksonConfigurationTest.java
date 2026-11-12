package com.bms.review.interfaces;

import com.bms.review.domain.CheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckItemJacksonConfigurationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void draftAcceptsBlankNullAndOmittedResults() throws Exception {
        var request = mapper.readValue("""
                {"items":[{"itemId":1,"result":""},{"itemId":2,"result":"  "},
                          {"itemId":3,"result":null},{"itemId":4},{"itemId":5,"result":"FAIL"}]}
                """, CheckItemController.SaveCheckItemsRequest.class);

        assertThat(request.items()).extracting(CheckItemController.SaveCheckItemRequest::result)
                .containsExactly(null, null, null, null, CheckResult.FAIL);
    }

    @Test
    void draftRejectsUnknownResults() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"items":[{"itemId":1,"result":"UNKNOWN"}]}
                """, CheckItemController.SaveCheckItemsRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }
}
