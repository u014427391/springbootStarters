package org.framework.starter.resttemplate;

import cn.hutool.json.JSONUtil;
import org.framework.starter.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
class ResttemplateSpringBootStarterApplicationTests {

    @Autowired
    private RestTemplate restTemplate;

    @Test
    void contextLoads() {
        String username="mojombo";
        String url = new StringBuilder()
                .append("https://api.github.com/users/")
                .append(username)
                .toString();
        UserDto userDto = restTemplate.getForObject(url , UserDto.class , "");
        System.out.println(JSONUtil.toJsonPrettyStr(userDto));

    }

}
