package com.khatapilot.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BacktestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void backtestPageRendersWithSyntheticNotice() throws Exception {
        mockMvc.perform(get("/backtest"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("synthetic history")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Backtest")));
    }
}
