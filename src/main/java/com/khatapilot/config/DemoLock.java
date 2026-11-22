package com.khatapilot.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Makes the demo operations (bad-day load and reset) mutually exclusive. A caller that finds the lock held gets
 * {@code false} back immediately and the action is not run.
 */
@Component
public class DemoLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Runs the action while holding the lock. Returns {@code false} without running it if another demo is running. */
    public boolean tryRun(Runnable action) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }
}
