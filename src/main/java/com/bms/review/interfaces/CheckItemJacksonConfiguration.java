package com.bms.review.interfaces;

import com.bms.review.domain.CheckResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** 互检单草稿请求使用的 Jackson 反序列化配置。 */
public final class CheckItemJacksonConfiguration {
    private CheckItemJacksonConfiguration() {
    }

    public static final class DraftCheckResultDeserializer extends StdDeserializer<CheckResult> {
        public DraftCheckResultDeserializer() {
            super(CheckResult.class);
        }

        @Override
        public CheckResult deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING) && parser.getText().isBlank()) {
                return null;
            }
            return context.readValue(parser, CheckResult.class);
        }
    }
}
