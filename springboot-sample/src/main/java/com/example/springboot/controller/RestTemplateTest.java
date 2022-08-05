package com.example.springboot.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@Slf4j
public class RestTemplateTest {

    @Autowired
    private RestTemplate restTemplate;

    @GetMapping(value = "getGithubUser")
    public String test() {
        String json = restTemplate.getForObject("https://api.github.com/users", String.class);
        log.info("json:[{}]" , json);
        return json;
    }


}
