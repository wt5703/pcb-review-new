package com.bms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description PCB评审平台后端服务的启动入口，负责装配各业务域配置并初始化 Spring Boot 运行环境。
 */


@SpringBootApplication
public class PcbReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(PcbReviewApplication.class, args);
    }
}
