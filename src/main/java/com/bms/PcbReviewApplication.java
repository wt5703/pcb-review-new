package com.bms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description PCB评审平台后端服务的启动入口，负责装配各业务域配置并初始化 Spring Boot 运行环境。
 */


@SpringBootApplication
@EnableScheduling
public class PcbReviewApplication {
    private static final Logger log = LoggerFactory.getLogger(PcbReviewApplication.class);
    private static final DateTimeFormatter START_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        SpringApplication.run(PcbReviewApplication.class, args);
        log.info("PCB评审平台启动完成，当前时间：{}", LocalDateTime.now().format(START_TIME_FORMAT));
    }
}
