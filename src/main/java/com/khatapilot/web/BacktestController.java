package com.khatapilot.web;

import com.khatapilot.backtest.BacktestService;
import com.khatapilot.backtest.BacktestService.BacktestResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Synthetic calibration backtest page for the cash forecast. */
@Controller
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/backtest")
    public String backtest(Model model) {
        BacktestResult result = backtestService.runBacktest();
        model.addAttribute("checkpoints", result.checkpoints());
        model.addAttribute("buckets", result.buckets());
        return "backtest";
    }
}
