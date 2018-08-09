package com.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class GmailApplication implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(GmailApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(GmailApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("Application started with command-line arguments: {}", Arrays.toString(args.getSourceArgs()));
        logger.info("NonOptionArgs: {}", args.getNonOptionArgs());
        logger.info("OptionNames: {}", args.getOptionNames());

        for (String name : args.getOptionNames()) {
            logger.info("arg-" + name + "=" + args.getOptionValues(name));
        }

        boolean containsOption = args.containsOption("store.directory.path");
        logger.info("Contains store.directory.path: " + containsOption);

        containsOption = args.containsOption("gmail.filter.query");
        logger.info("Contains store.directory.path: " + containsOption);
    }

}
