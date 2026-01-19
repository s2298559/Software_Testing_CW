package com.example.restservice;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SpringBootApplication
public class PizzaDronzApplication {


    public PizzaDronzApplication(ILPRestService ilpRestService) {
    }

    public static void main(String[] args) {
        SpringApplication.run(PizzaDronzApplication.class, args);
    }

}

