package com.bytedance.douyinclouddemo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class DouyincloudDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DouyincloudDemoApplication.class, args);
    }
}
