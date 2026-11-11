package com.apexchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point for the ApexChat real-time messaging server. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApexChatApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApexChatApplication.class, args);
  }
}
