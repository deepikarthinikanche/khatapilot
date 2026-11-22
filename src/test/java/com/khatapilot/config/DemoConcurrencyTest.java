package com.khatapilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Fires POST /demo/bad-day and POST /demo/reset at the same time and checks that both are handled cleanly. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoConcurrencyTest {

    private static final int ROUNDS = 5;
    private static final String BUSY_MESSAGE = "Demo is busy, try again";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concurrentBadDayAndResetAreRedirectedWithoutErrors() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<MvcResult> bad = pool.submit(fire(start, "/demo/bad-day"));
                Future<MvcResult> reset = pool.submit(fire(start, "/demo/reset"));
                start.countDown();
                List<MvcResult> results = List.of(bad.get(60, TimeUnit.SECONDS), reset.get(60, TimeUnit.SECONDS));

                int ran = 0;
                for (MvcResult result : results) {
                    assertThat(result.getResponse().getStatus()).as("round %d status", round).isBetween(300, 399);
                    assertThat(result.getResponse().getRedirectedUrl()).as("round %d redirect", round).isEqualTo("/");
                    Object message = result.getFlashMap().get("demoMessage");
                    assertThat(message == null || BUSY_MESSAGE.equals(message))
                            .as("round %d demoMessage was %s", round, message)
                            .isTrue();
                    if (message == null) {
                        ran++;
                    }
                }
                assertThat(ran).as("requests that ran in round %d", round).isGreaterThanOrEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private Callable<MvcResult> fire(CountDownLatch start, String path) {
        return () -> {
            start.await();
            return mockMvc.perform(post(path)).andReturn();
        };
    }
}
