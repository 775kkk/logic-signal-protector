package com.logicsignalprotector.apitelegram;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiTelegramServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiTelegramServiceApplication.class, args);
  }
}
