package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.UnidbgProperties;
import com.github.unidbg.worker.Worker;
import com.github.unidbg.worker.WorkerPool;
import com.github.unidbg.worker.WorkerPoolFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service("temuWorker")
public class TemuServiceWorker extends Worker {

    private UnidbgProperties unidbgProperties;
    private WorkerPool pool;
    private TemuService temuService;


    public TemuServiceWorker(WorkerPool pool) {
        super(pool);

    }

    @Autowired
    public TemuServiceWorker(UnidbgProperties unidbgProperties,
                             @Value("${spring.task.execution.pool.core-size:4}") int poolSize) {
        super(null);
        this.unidbgProperties = unidbgProperties;
        if (this.unidbgProperties.isAsync()) {
            pool = WorkerPoolFactory.create((pool) ->
                    new TemuServiceWorker(unidbgProperties.isDynarmic(), unidbgProperties.isVerbose(),pool),
                Math.max(poolSize, 4));
            log.info("线程池为:{}", Math.max(poolSize, 4));
        } else {
            this.temuService = new TemuService(unidbgProperties);
        }
    }

    public TemuServiceWorker(boolean dynarmic, boolean verbose, WorkerPool pool) {
        super(pool);
        this.unidbgProperties = new UnidbgProperties();
        unidbgProperties.setDynarmic(dynarmic);
        unidbgProperties.setVerbose(verbose);
        log.info("是否启用动态引擎:{},是否打印详细信息:{}", dynarmic, verbose);
        this.temuService = new TemuService(unidbgProperties);
    }

    @Async
    @SneakyThrows
    public CompletableFuture<byte[]> ttEncrypt(String input) {

        TemuServiceWorker worker;
        byte[] data;
        if (this.unidbgProperties.isAsync()) {
            while (true) {
                if ((worker = pool.borrow(2, TimeUnit.SECONDS)) == null) {
                    continue;
                }
                data = worker.doWork(input);
                pool.release(worker);
                break;
            }
        } else {
            synchronized (this) {
                data = this.doWork(input);
            }
        }
        return CompletableFuture.completedFuture(data);
    }

    private byte[] doWork(String input) {
        return temuService.ttEncrypt(input);
    }

    @SneakyThrows @Override public void destroy() {
        temuService.destroy();
    }
}
