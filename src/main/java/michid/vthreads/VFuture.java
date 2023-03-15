package michid.vthreads;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@code VFuture} is a handle to the result of an asynchronous computation.
 * It captures the effects of latency and failure: it is {@code completed} once the
 * computation finishes (either successfully or with a failure). Additionally, it
 * {@code succeeded} or {@code failed} depending on the outcome of the computation.
 * When completed, it provides access to the outcome of the computation.
 * <p>
 * Internally each {@code VFuture} instance is backed by a virtual thread that is
 * responsible for producing the future's result.
 */
public class VFuture<T> {

    private final Thread thread;

    private volatile Result<T> result;

    private static final Thread NOOP = Thread.startVirtualThread(() -> {});

    private record Result<T>(Throwable exception, T value) {
        private static <T> Result<T> success(T value) { return new Result<>(null, value); }
        private static <T> Result<T> failure(Throwable exception) { return new Result<>(exception, null); }
    }

    private VFuture(Result<T> result) {
        thread = NOOP;
        this.result = result;
    }

    private VFuture(Callable<T> task, BiConsumer<Throwable, T> onComplete) {
        this.thread = Thread.startVirtualThread(() -> {
            try {
                result = Result.success(task.call());
                onComplete.accept(null, result.value);
            } catch (Throwable e) {
                result = Result.failure(e);
                onComplete.accept(result.exception, null);
            }
        });
    }

    private VFuture(Callable<T> task) {
        this(task, (throwable, t) -> {});
    }

    /**
     * Factory for creating a new instance backed by a {@code Callable}. The callable
     * is evaluated asynchronously completing the returned instance when finished.
     * The returned instance either succeeds with the value returned by the callable
     * or fails with the exception thrown by the callable.
     *
     * @param task  the callable to run asynchronously
     * @param onComplete  a callback to call once this instance completes
     */
    public static <T> VFuture<T> future(Callable<T> task, BiConsumer<Throwable, T> onComplete) {
        return new VFuture<>(task, onComplete);
    }

    /**
     * Factory for creating a new instance backed by a {@code Callable}. The callable
     * is evaluated asynchronously completing the returned instance when finished.
     * The returned instance either succeeds with the value returned by the callable
     * or fails with the exception thrown by the callable.
     *
     * @param task  the callable to run asynchronously
     */
    public static <T> VFuture<T> future(Callable<T> task) {
        return new VFuture<>(task);
    }

    /**
     * Factory for creating an instance that already completed successfully with  a given value.
     *
     * @param value  the value to complete with
     * @return  a new instance
     */
    public static <T> VFuture<T> succeeded(T value) {
        return new VFuture<>(Result.success(value));
    }

    /**
     * Factory for creating an instance that already completed with a failure
     *
     * @param failure  the exception to fail with
     * @return  a new instance
     */
    public static <T> VFuture<T> failed(Throwable failure) {
        return new VFuture<>(Result.failure(failure));
    }

    public static <T> VFuture<T> fromFuture(Future<T> future) {
        return VFuture.future(future::get);
    }

    /**
     * @return  a {@code CompletableFuture} that completes either successfully or with
     *          a failure if after this future completed either successfully or with a
     *          failure.
     */
    public CompletableFuture<T> toCompletableFuture() {
        var future = new CompletableFuture<T>();
        onFail(future::completeExceptionally);
        onSuccess(future::complete);
        return future;
    }

    /**
     * @return  {@code true} if this instance completed (either successfully of with a failure).
     */
    public boolean isCompleted() {
        return result != null;
    }

    /**
     * @return  {@code true} if this instance completed successfully.
     */
    public boolean isSucceeded() {
        return isCompleted() && result.value != null;
    }

    /**
     * @return  {@code true} if this instance completed with a failure.
     */
    public boolean isFailed() {
        return isCompleted() && result.exception != null;
    }

    /**
     * Map a function over a future in the success case: turn a {@code BetterFuture<T>}
     * into a {@code BetterFuture<R>} given a {@code Function<T, R>}.
     * <p>
     * Create a new {@code BetterFuture} that applies a function to its result if this
     * instance succeeds and otherwise fails with the exception of this instance.
     * <p>Example:
     * <pre>
     *   BetterFuture&lt;String> future = ...
     *   BetterFuture&lt;Integer> intFuture = future.map(Integer::parseInt);
     * </pre>
     *
     * @param fn  function to apply
     * @return  new {@code BetterFuture} instance
     */
    public <R> VFuture<R> map(Function<T, R> fn) {
        return new VFuture<>(() -> fn.apply(get()));
    }

    /**
     * Map a function over a future in the failure case.
     * <p>
     * Create a new {@code BetterFuture} that applies a function to its result if this
     * instance fails and otherwise completes with the value of this instance.
     *
     * @param fn  function to apply
     * @return  new {@code BetterFuture} instance
     */
    public VFuture<T> recover(Function <Throwable, T> fn) {
        return new VFuture<>(() -> {
            try {
                return get();
            } catch (Throwable t) {
                return fn.apply(t);
            }
        });
    }

    /**
     * Create a new {@code BetterFuture} and complete it with a given value if this
     * instance fails. Otherwise, complete it with the value of this instance.
     * <p>
     * This is equivalent to
     * <pre>
     *     future.recover(ignore -> value);
     * </pre>
     *
     * @param value  the value to complete in case of this instance failing
     * @return  new {@code BetterFuture} instance
     */
    public VFuture<T> recover(T value) {
        return recover(__ -> value);
    }

    /**
     * Flatmap a function over a future in the success case: turn a {@code BetterFuture<T>}
     * into a {@code BetterFuture<R>} given a {@code Function<T, BetterFuture<R>>}.
     * <p>
     * Apply a function to the value of this future when it completes successfully and return a
     * new {@code BetterFuture} that completes when the future returned from the function
     * completes.
     * <p>
     * Use {@code andThen} to execute asynchronous calls that depend on each other's result
     * sequentially. (See {@link #andAlso(VFuture)} for parallel execution.)
     * <p>Example:
     * <pre>
     * BetterFuture&lt;String> productReview = BetterFuture.future(
     *     API::getProduct)
     *         .andThen(product -> BetterFuture.future(() ->
     *     API.getRating(product))
     *         .map(rating ->
     *     newProductReview(product, rating)));
     * </pre>
     *
     * @param fn  function to apply
     * @return  new {@code BetterFuture} instance
     */
    public <R> VFuture<R> andThen(Function<T, VFuture<R>> fn) {
        return new VFuture<>(() -> fn.apply(get()).get());
    }

    /**
     * Flatmap a function over a future in the failure case.
     * <p>
     * Apply a function to the value of this future when it completes with a failure and return a
     * new {@code BetterFuture} that completes when the future returned from the function
     * completes.
     *
     * @param fn  function to apply
     * @return  new {@code BetterFuture} instance
     */
    public VFuture<T> recoverWith(Function<Throwable, VFuture<T>> fn) {
        return new VFuture<>(() -> {
            try {
                return get();
            } catch (Throwable t) {
                return fn.apply(t).get();
            }
        });
    }

    /**
     * Use {@code andAlso} to execute asynchronous calls that do not depend on each other's result
     * in parallel. (See {@link #andThen(Function)} for sequential execution.)
     * <p>Example:
     * <pre>
     * BetterFuture<Integer> duration = BetterFuture.future(
     *     API::getSpeed)
     *         .andAlso(BetterFuture.future(
     *     API::getDistance)
     *         .map(distance -> speed ->
     *     distance / speed));
     * </pre>
     *
     * @param future  a future of a function receiving the result of this instance for its argument
     * @return  new {@code BetterFuture} instance
     *
     * @see #andAlso(VFuture, BiFunction)
     */
    public <R> VFuture<R> andAlso(VFuture<Function<T, R>> future) {
        return future.andThen(this::map);
    }

    /**
     * Use {@code andAlso} to execute asynchronous calls that do not depend on each other's result
     * in parallel. (See {@link #andThen(Function)} for sequential execution.)
     * <p>Example:
     * <pre>
     * BetterFuture<Integer> duration = BetterFuture.future(
     *     API::getSpeed)
     *         .andAlso(BetterFuture.future(
     *     API::getDistance),
     *         (distance, speed) ->
     *     distance / speed);
     * </pre>
     *
     * @param future  future to run in parallel to this future
     * @param f  binary function for combining the results of the individual futures
     * @return  new {@code BetterFuture} instance
     *
     * @see #andAlso(VFuture)
     */
    public <S, R> VFuture<R> andAlso(VFuture<S> future, BiFunction<T, S, R> f) {
        return future.andAlso(map(t -> s -> f.apply(t, s)));
    }

    /**
     * Get the value of this future. This call block until this instance completes.
     *
     * @return  value of this future
     * @throws ExecutionException  if this instance completes with a failure
     * @throws InterruptedException  if waiting for completion is interrupted
     */
    public T get() throws InterruptedException, ExecutionException {
        thread.join();
        if (result.exception != null) {
            throw (result.exception instanceof ExecutionException executionException)
                  ? executionException
                  : new ExecutionException(result.exception);
        } else {
            return result.value;
        }
    }

    /**
     * Get the value of this future. This call block until this instance completes or until it
     * times out.
     *
     * @param timeout  time to wait for this instance to complete.
     * @return  value of this future
     * @throws ExecutionException  if this instance completes with a failure
     * @throws InterruptedException  if waiting for completion is interrupted
     */
    public Optional<T> get(Duration timeout) throws InterruptedException, ExecutionException {
        if (!timeout.isZero()) {
            thread.join(timeout.toMillis());
        }
        if (result == null) {
            return Optional.empty();
        } else if (result.value != null) {
            return Optional.of(result.value);
        } else {
            throw new ExecutionException(result.exception);
        }
    }

    /**
     * Install a callback receiving this instance as an argument once it completed.
     *
     * @param callback  the callback to call once this instance completes
     * @return  this instance
     */
    public VFuture<T> onComplete(Consumer<VFuture<T>> callback) {
        return new VFuture<>(this::get,
             (throwable, t) -> callback.accept(VFuture.this));
    }

    /**
     * Install a callback receiving the value of this instance as an argument once it completes
     * successfully.
     *
     * @param callback  the callback to call once this instance completes
     * @return  this instance
     */
    public VFuture<T> onSuccess(Consumer<T> callback) {
        return onComplete(future -> {
            if (future.isSucceeded()) {
                callback.accept(future.result.value);
            }
        });
    }

    /**
     * Install a callback receiving the exception of this instance as an argument once it completes
     * with a failure.
     *
     * @param callback  the callback to call once this instance completes
     * @return  this instance
     */
    public VFuture<T> onFail(Consumer<Throwable> callback) {
        return onComplete(future -> {
            if (future.isFailed()) {
                callback.accept(future.result.exception);
            }
        });
    }

    /**
     * Collect a stream of futures in their order of completion into a blocking queue.
     *
     * @param futures  a stream of futures
     * @return  a blocking queue containing the futures in the order of their completion.
     */
    public static <T> BlockingQueue<VFuture<T>> collect(Stream<VFuture<T>> futures) {
        var queue = new LinkedBlockingQueue<VFuture<T>>();
        futures.forEach(future ->
            future.onComplete(queue::add));

        return queue;
    }

    /**
     * Reduce a stream of futures into a future of a stream in parallel. The returned future
     * completes successfully if all futures on the stream complete successfully and fails
     * otherwise.
     *
     * @param futures  a stream of futures
     * @return  a future for a stream of all the values of the completed futures
     */
    public static <T> VFuture<Stream<T>> reduce(Stream<VFuture<T>> futures) {
        return futures
            .map(future -> future.map(Stream::of))
            .reduce(
                VFuture.succeeded(Stream.empty()),
                (future1, future2) ->
                    future1.andAlso(
                        future2, Stream::concat));
    }

    /**
     * Left associative parallel fold of a list of futures into a single future.
     *
     * @param futures  a stream of futures
     * @param init  initial future to start the fold with
     * @param combine  binary function for combining the values of two futures
     * @return  a future for the folded value
     */
    public static <R, T> VFuture<R> foldLeft(Stream<VFuture<T>> futures, VFuture<R> init, BiFunction<R, T, R> combine) {
        return futures.collect(
            Collectors.collectingAndThen(
                // Force associativity by lifting fold into the monoid of functions
                Collectors.reducing(
                    Function.<VFuture<R>>identity(),
                    t -> r -> r.andAlso(t, combine),
                    Function::andThen),
                endo -> endo.apply(init)));
    }

    /**
     * Find the first completed future in a stream of futures in parallel.
     *
     * @param futures  a stream of futures
     * @return  a future that completes when the first future completes
     */
    public static <T> VFuture<T> first(Stream<VFuture<T>> futures) {
        var promise = new Promise<T>();

        futures.forEach(future ->
            future
                .onSuccess(promise::succeed)
                .onFail(promise::fail));

        return new VFuture<>(promise);
    }

    /**
     * An {@code Promise<T>} is the promise for a future value of {@code T}.
     * Instances of this class can be passed to {@link VFuture#future(Callable)}.
     * The returned future completes ones the promise is fulfilled by either
     * calling {@link #succeed(Object)} or {@link #fail(Throwable)}.
     */
    public static class Promise<T> implements Callable<T> {
        private Result<T> result;

        /**
         * Fulfill this promise with the given {@code value}.
         * @param value
         */
        public void succeed(T value) {
            complete(Result.success(value));
        }

        /**
         * Fulfill this promise with the given {@code failure}.
         * @param failure
         */
        public void fail(Throwable failure) {
            complete(Result.failure(failure));
        }

        private synchronized void complete(Result<T> result) {
            if (this.result == null) {
                this.result = result;
            }
            notifyAll();
        }

        /**
         * Wait for the promise to fulfill.
         * @return  the value with which the promise was fulfilled
         * @throws InterruptedException
         * @throws ExecutionException  when the promise was fulfilled with a failure
         */
        @Override
        public synchronized T call() throws InterruptedException, ExecutionException {
            while (result == null) {
                wait();
            }
            if (result.value != null) {
                return result.value;
            } else {
                throw new ExecutionException(result.exception);
            }
        }
    }

}
