package com.example.logbackmdclab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class LogbackMdcLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogbackMdcLabApplication.class, args);
    }
}
