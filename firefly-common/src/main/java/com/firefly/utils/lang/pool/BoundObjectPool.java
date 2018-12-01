package com.firefly.utils.lang.pool;

import com.firefly.utils.concurrent.Atomics;
import com.firefly.utils.concurrent.Scheduler;
import com.firefly.utils.concurrent.Schedulers;
import com.firefly.utils.function.Action0;
import com.firefly.utils.lang.AbstractLifeCycle;
import com.firefly.utils.lang.track.FixedTimeLeakDetector;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Pengtao Qiu
 */
public class BoundObjectPool<T> extends AbstractLifeCycle implements Pool<T> {

    protected final int maxSize;
    protected final long timeout; // unit second
    protected final TimeUnit timeUnit = TimeUnit.SECONDS;
    protected final BlockingQueue<PooledObject<T>> queue;
    protected final ExecutorService gettingService;
    protected final ExecutorService releaseService;
    protected final Scheduler creatingScheduler;
    protected final ObjectFactory<T> objectFactory;
    protected final Validator<T> validator;
    protected final Dispose<T> dispose;
    protected final FixedTimeLeakDetector<PooledObject<T>> leakDetector;
    protected final AtomicInteger createdCount = new AtomicInteger(0);

    public BoundObjectPool(int maxSize, long timeout, long leakDetectorInterval, long releaseTimeout,
                           ObjectFactory<T> objectFactory, Validator<T> validator, Dispose<T> dispose,
                           Action0 noLeakCallback) {
        this(maxSize, timeout, leakDetectorInterval, releaseTimeout,
                4, 2,
                objectFactory, validator, dispose,
                noLeakCallback);
    }

    public BoundObjectPool(int maxSize, long timeout, long leakDetectorInterval, long releaseTimeout,
                           int maxGettingThreadNum, int maxReleaseThreadNum,
                           ObjectFactory<T> objectFactory, Validator<T> validator, Dispose<T> dispose,
                           Action0 noLeakCallback) {
        this(maxSize, timeout,
                new ArrayBlockingQueue<>(maxSize),
                new ThreadPoolExecutor(1, maxGettingThreadNum,
                        30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(10000),
                        r -> new Thread(r, "firefly-asynchronous-pool-getting-thread")),
                new ThreadPoolExecutor(1, maxReleaseThreadNum,
                        30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(10000),
                        r -> new Thread(r, "firefly-asynchronous-pool-release-thread")),
                objectFactory, validator, dispose, new FixedTimeLeakDetector<>(leakDetectorInterval, releaseTimeout, noLeakCallback));
    }

    public BoundObjectPool(int maxSize, long timeout,
                           BlockingQueue<PooledObject<T>> queue,
                           ExecutorService gettingService, ExecutorService releaseService,
                           ObjectFactory<T> objectFactory, Validator<T> validator, Dispose<T> dispose,
                           FixedTimeLeakDetector<PooledObject<T>> leakDetector) {
        this.maxSize = maxSize;
        this.timeout = timeout;
        this.queue = queue;
        this.gettingService = gettingService;
        this.releaseService = releaseService;
        this.objectFactory = objectFactory;
        this.validator = validator;
        this.dispose = dispose;
        this.leakDetector = leakDetector;
        this.creatingScheduler = Schedulers.createScheduler();
        start();
    }

    @Override
    public CompletableFuture<PooledObject<T>> asyncGet() {
        CompletableFuture<PooledObject<T>> future = new CompletableFuture<>();
        gettingService.submit(() -> {
            try {
                PooledObject<T> object = get();
                future.complete(object);
            } catch (InterruptedException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public PooledObject<T> get() throws InterruptedException {
        PooledObject<T> pooledObject = createNewIfLessThanMaxSize();
        if (pooledObject != null) {
            initGettingFromThePool(pooledObject);
            return pooledObject;
        } else {
            pooledObject = queue.poll(timeout, timeUnit);
            if (pooledObject != null) {
                if (isValid(pooledObject)) {
                    initGettingFromThePool(pooledObject);
                    return pooledObject;
                } else {
                    destroy(pooledObject);
                    pooledObject = createNewIfLessThanMaxSize();
                    if (pooledObject != null) {
                        initGettingFromThePool(pooledObject);
                        return pooledObject;
                    }
                }
            }
        }
        throw new IllegalStateException("Can not get the PooledObject");
    }

    @Override
    public void release(PooledObject<T> pooledObject) {
        if (pooledObject.released.compareAndSet(false, true)) {
            releaseService.submit(() -> releaseSync(pooledObject));
        }
    }

    private synchronized void releaseSync(PooledObject<T> pooledObject) {
        try {
            boolean success = queue.offer(pooledObject, timeout, timeUnit);
            if (!success) {
                destroy(pooledObject);
            }
        } catch (InterruptedException e) {
            destroy(pooledObject);
        } finally {
            clearLeakTrack(pooledObject);
        }
    }

    private synchronized void destroy(PooledObject<T> pooledObject) {
        dispose.destroy(pooledObject);
        Atomics.getAndDecrement(createdCount, 0);
    }

    private synchronized PooledObject<T> createNewIfLessThanMaxSize() {
        if (createdCount.get() < maxSize) {
            try {
                PooledObject<T> pooledObject = objectFactory.createNew(this).get(timeout, timeUnit);
                createdCount.incrementAndGet();
                return pooledObject;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                System.err.println(e.getMessage());
                return null;
            }
        } else {
            return null;
        }
    }

    private void initGettingFromThePool(PooledObject<T> pooledObject) {
        registerLeakTrack(pooledObject);
        pooledObject.released.set(false);
    }

    private void registerLeakTrack(PooledObject<T> pooledObject) {
        leakDetector.register(pooledObject, p -> {
            createdCount.decrementAndGet();
            pooledObject.leakCallback.call(pooledObject);
        });
    }

    private void clearLeakTrack(PooledObject<T> pooledObject) {
        leakDetector.clear(pooledObject);
    }

    @Override
    public boolean isValid(PooledObject<T> pooledObject) {
        return validator.isValid(pooledObject);
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public FixedTimeLeakDetector<PooledObject<T>> getLeakDetector() {
        return leakDetector;
    }

    @Override
    public int getCreatedObjectCount() {
        return createdCount.get();
    }

    @Override
    protected void init() {
        creatingScheduler.scheduleWithFixedDelay(() -> {
            for (int i = 0; i < maxSize; i++) {
                PooledObject<T> pooledObject = createNewIfLessThanMaxSize();
                if (pooledObject != null) {
                    boolean success = queue.offer(pooledObject);
                    if (!success) {
                        destroy(pooledObject);
                    }
                }
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    protected void destroy() {
        creatingScheduler.stop();
        gettingService.shutdown();
        releaseService.shutdown();
        leakDetector.stop();
    }
}
