package com.runevault;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@SpringBootApplication
@EnableConfigurationProperties({TransactionPolicy.class, RateLimitProperties.class})
public class RunevaultApplication {
 public static void main(String[] args) { SpringApplication.run(RunevaultApplication.class, args); }
}
