package org.nineai.engdrawing;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 抽取/生成共享重任务槽：ODA、Python、OCR 不宜无界并行。
 * 轻操作（截图识别/翻译/层次保存）另有独立轻槽，满则立刻拒绝，避免占死 Tomcat。
 */
@Component
public class HeavyJobSlots {

    private final Semaphore global;
    private final Semaphore extract;
    private final Semaphore compose;
    private final Semaphore light;
    private final int extractMax;
    private final int composeMax;
    private final int extractMaxQueued;
    private final int composeMaxQueued;
    private final int lightMax;
    private int extractWaiting;
    private int extractRunning;
    private int composeWaiting;
    private int composeRunning;
    private final ExecutorService executor;
    private final Object lock = new Object();

    public HeavyJobSlots(
            @Value("${ai-drawing.heavy.global-slots:3}") int globalSlots,
            @Value("${ai-drawing.heavy.extract-max-running:3}") int extractMax,
            @Value("${ai-drawing.heavy.compose-max-running:2}") int composeMax,
            @Value("${ai-drawing.heavy.extract-max-queued:15}") int extractMaxQueued,
            @Value("${ai-drawing.heavy.compose-max-queued:10}") int composeMaxQueued,
            @Value("${ai-drawing.heavy.light-max-running:4}") int lightMax) {
        this.extractMax = Math.max(1, extractMax);
        this.composeMax = Math.max(1, composeMax);
        this.extractMaxQueued = Math.max(0, extractMaxQueued);
        this.composeMaxQueued = Math.max(0, composeMaxQueued);
        this.lightMax = Math.max(1, lightMax);
        int g = Math.max(1, globalSlots);
        this.global = new Semaphore(g, true);
        this.extract = new Semaphore(this.extractMax, true);
        this.compose = new Semaphore(this.composeMax, true);
        this.light = new Semaphore(this.lightMax, true);
        int threads = this.extractMax + this.extractMaxQueued + this.composeMax + this.composeMaxQueued;
        this.executor = Executors.newFixedThreadPool(Math.max(4, threads), r -> {
            Thread t = new Thread(r, "eng-heavy");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean tryAdmitExtract() {
        synchronized (lock) {
            if (extractRunning + extractWaiting >= extractMax + extractMaxQueued) {
                return false;
            }
            extractWaiting++;
            return true;
        }
    }

    public boolean tryAdmitCompose() {
        synchronized (lock) {
            if (composeRunning + composeWaiting >= composeMax + composeMaxQueued) {
                return false;
            }
            composeWaiting++;
            return true;
        }
    }

    public int extractWaitingCount() {
        synchronized (lock) {
            return extractWaiting;
        }
    }

    public int composeWaitingCount() {
        synchronized (lock) {
            return composeWaiting;
        }
    }

    public void acquireExtract() throws InterruptedException {
        extract.acquire();
        try {
            global.acquire();
        } catch (InterruptedException e) {
            extract.release();
            throw e;
        }
        synchronized (lock) {
            if (extractWaiting > 0) {
                extractWaiting--;
            }
            extractRunning++;
        }
    }

    public void acquireCompose() throws InterruptedException {
        compose.acquire();
        try {
            global.acquire();
        } catch (InterruptedException e) {
            compose.release();
            throw e;
        }
        synchronized (lock) {
            if (composeWaiting > 0) {
                composeWaiting--;
            }
            composeRunning++;
        }
    }

    public void releaseExtract() {
        synchronized (lock) {
            if (extractRunning > 0) {
                extractRunning--;
            }
        }
        global.release();
        extract.release();
    }

    public void releaseCompose() {
        synchronized (lock) {
            if (composeRunning > 0) {
                composeRunning--;
            }
        }
        global.release();
        compose.release();
    }

    public void cancelAdmitExtract() {
        synchronized (lock) {
            if (extractWaiting > 0) {
                extractWaiting--;
            }
        }
    }

    public void cancelAdmitCompose() {
        synchronized (lock) {
            if (composeWaiting > 0) {
                composeWaiting--;
            }
        }
    }

    /**
     * 轻操作瞬时占槽（不等待）。满则 false，由调用方回 429 友好提示。
     */
    public boolean tryAcquireLight() {
        return light.tryAcquire();
    }

    public void releaseLight() {
        light.release();
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public java.util.concurrent.Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
