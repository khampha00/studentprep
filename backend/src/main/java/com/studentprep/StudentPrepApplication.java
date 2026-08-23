package com.studentprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableAsync
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class StudentPrepApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudentPrepApplication.class, args);
    }
}
