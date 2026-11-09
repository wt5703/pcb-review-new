package com.bms.identity.infrastructure;

import com.bms.common.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 公司用户中心的 Feign 客户端，服务名和接口路径均由环境配置提供。 */
@FeignClient(
        name = "${user-center.service-name:lpenergy-portal-server}",
        path = "${user-center.api-prefix:/leapenergy/portal}",
        configuration = FeignConfig.class
)
public interface UserCenterFeignClient {

    @GetMapping("${user-center.user-profile-path:/user/{employeeNo}}")
    UserCenterUserProfileClient.UserCenterResponse getByEmployeeNo(@PathVariable("employeeNo") String employeeNo);
}
