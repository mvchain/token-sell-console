package com.mvc.sell.console.job;

import com.mvc.sell.console.service.TransactionService;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * @author qyc
 */
@Component
@Order(value = 1)
@Log
public class MyStartupRunner implements CommandLineRunner {

    @Autowired
    TransactionService transactionService;


    @Override
    @Async
    public void run(String... args) {
        try {
            transactionService.initConfig();
            transactionService.startHistory();
//        transactionService.startListen();
            while (true) {
                transactionService.startTransaction();
                Thread.sleep(1);
            }
        } catch (Exception e) {
            transactionService.startHistory();
        }
    }

}