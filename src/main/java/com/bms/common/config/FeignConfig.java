package com.bms.common.config;

import feign.Request;
import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.Bean;


/**
 * @author wangtao
 * @date 2026/10/15 11:47
 */

public class FeignConfig {
    @Bean
    public FeignClientProperties feignClientProperties() {
        return new FeignClientProperties();
    }

    @Bean
    public Encoder feignFormEncoder() {
        return new SpringFormEncoder();
    }

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(5000, 30000);
    }

}
