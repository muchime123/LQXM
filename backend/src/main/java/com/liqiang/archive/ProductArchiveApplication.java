package com.liqiang.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProductArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductArchiveApplication.class, args);
    }
}
