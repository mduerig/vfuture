package michid.vthreads;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Stream.concat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import michid.vthreads.VFuture.Promise;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class VFutureTest {

    @Test
    @Timeout(1)
    public void succeeded() throws ExecutionException, InterruptedException {
        VFuture<String> future = VFuture.succeeded("done");
        assertEquals("done", future.get());
        assertTrue(future.isCompleted());
        assertTrue(future.isSucceeded());
        assertFalse(future.isFailed());
        assertEquals("done", future.get());
    }

    @Test
    @Timeout(1)
    public void failed() {
        VFuture<String> future = VFuture.failed(new Exception("fail"));
        assertThrows(ExecutionException.class, future::get);
        assertTrue(future.isCompleted());
        assertTrue(future.isFailed());
        assertFalse(future.isSucceeded());
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void fromCallable() throws ExecutionException, InterruptedException {
        VFuture<String> future = VFuture.future(() -> "done");
        assertEquals("done", future.get());
        assertTrue(future.isCompleted());
        assertFalse(future.isFailed());
        assertTrue(future.isSucceeded());
    }

    @Test
    @Timeout(1)
    public void fromCallableWithException() {
        VFuture<String> future = VFuture.future(() -> {throw new Exception("fail");});
        assertThrows(ExecutionException.class, future::get);
        assertTrue(future.isCompleted());
        assertTrue(future.isFailed());
        assertFalse(future.isSucceeded());
    }

    @Test
    @Timeout(1)
    public void fromCallableAsync() throws ExecutionException, InterruptedException {
        var promise = new Promise<String>();

        VFuture<String> future = VFuture.future(promise);

        assertEquals(Optional.empty(), future.get(Duration.ofMillis(100)));
        assertFalse(future.isCompleted());
        assertFalse(future.isFailed());
        assertFalse(future.isSucceeded());

        promise.succeed("done");

        assertEquals("done", future.get());
        assertTrue(future.isCompleted());
        assertFalse(future.isFailed());
        assertTrue(future.isSucceeded());
        assertTrue(future.isCompleted());
    }

    @Test
    @Timeout(1)
    public void toCompletableFutureSuccess() throws ExecutionException, InterruptedException {
        var promise = new Promise<String>();
        var future = VFuture.future(promise);
        var completableFuture = future.toCompletableFuture();

        assertFalse(completableFuture.isDone());

        promise.succeed("success");

        assertEquals("success", completableFuture.get());
        assertTrue(completableFuture.isDone());
        assertFalse(completableFuture.isCompletedExceptionally());
    }

    @Test
    @Timeout(1)
    public void toCompletableFutureFailure() {
        var promise = new Promise<String>();
        var future = VFuture.future(promise);
        var completableFuture = future.toCompletableFuture();

        assertFalse(completableFuture.isDone());

        promise.fail(new RuntimeException("fail"));

        assertThrows(ExecutionException.class, completableFuture::get);
        assertTrue(completableFuture.isDone());
        assertTrue(completableFuture.isCompletedExceptionally());
    }

    @Test
    @Timeout(1)
    public void mapSucceeded() throws ExecutionException, InterruptedException {
        VFuture<String> future = VFuture.succeeded(42).map(Object::toString);
        assertEquals("42", future.get());
    }

    @Test
    @Timeout(1)
    public void mapFailed() {
        VFuture<String> future = VFuture.failed(new Exception("fail")).map(Object::toString);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void recover() throws ExecutionException, InterruptedException {
        var future =
            VFuture.failed(new Exception("fail"))
                .recover(Throwable::getMessage);

        assertEquals("java.lang.Exception: fail", future.get());
    }

    @Test
    @Timeout(1)
    public void andThen() throws ExecutionException, InterruptedException {
        VFuture<String> future =
            VFuture.future(() -> "a")
                .andThen(string1 ->
                             VFuture.future(() -> string1 + "b")
                                 .andThen(string2 ->
                                              VFuture.future(() -> string2 + "c")));

        assertEquals("abc", future.get());
    }

    @Test
    @Timeout(1)
    public void andThenWithFirstFailing() {
        AtomicBoolean shortCut = new AtomicBoolean(true);
        VFuture<Boolean> future =
            VFuture.future(() -> { throw new Exception("fail"); })
                .andThen(__ ->
                             VFuture.future(() -> shortCut.getAndSet(false)));

        assertThrows(ExecutionException.class, future::get);
        assertTrue(shortCut.get());
    }

    @Test
    @Timeout(1)
    public void andThenWithSecondFailing() {
        var future =
            VFuture.future(() -> "a")
                .andThen(__ ->
                             VFuture.future(() -> { throw new Exception("fail"); }));

        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void recoverWith() throws ExecutionException, InterruptedException {
        var future =
            VFuture.failed(new Exception("fail"))
                .recoverWith(__ -> VFuture.succeeded("success"));

        assertEquals("success", future.get());
    }

    @Test
    @Timeout(1)
    public void andAlso() throws ExecutionException, InterruptedException {
        CountDownLatch allRunning = new CountDownLatch(3);

        VFuture<String> future =
            VFuture.future(() -> waitForLatchAndReturn(allRunning, "a"))
                .andAlso(
                    VFuture.future(() -> waitForLatchAndReturn(allRunning, "b"))
                        .andAlso(
                            VFuture.future(() -> waitForLatchAndReturn(allRunning, "c"))
                                .map(string1 -> string2 -> string3 ->
                                    string3 + string2 + string1)));

        assertEquals("abc", future.get());
    }

    @Test
    @Timeout(1)
    public void andAlsoWithBiFunction() throws ExecutionException, InterruptedException {
        CountDownLatch allRunning = new CountDownLatch(2);

        VFuture<String> future =
            VFuture.future(() -> waitForLatchAndReturn(allRunning, "a"))
                .andAlso(
                    VFuture.future(() -> waitForLatchAndReturn(allRunning, "b")),
                    (string1, string2) -> string1 + string2);

        assertEquals("ab", future.get());
    }

    @Test
    @Timeout(1)
    public void andAlsoWithFailure() {
        CountDownLatch allRunning = new CountDownLatch(3);

        VFuture<String> future =
            VFuture.future(() -> waitForLatchAndReturn(allRunning, "a"))
                .andAlso(
                    VFuture.future(() -> waitForLatchAndFail(allRunning, new Exception("fail")))
                        .andAlso(
                            VFuture.future(() -> waitForLatchAndReturn(allRunning, "c"))
                                .map(string1 -> string2 -> string3 ->
                                    string3 + string2 + string1)));

        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void andAlsoWithBiFunctionAndFailure() {
        CountDownLatch allRunning = new CountDownLatch(2);

        VFuture<String> future =
            VFuture.future(() -> waitForLatchAndReturn(allRunning, "a"))
                .andAlso(
                    VFuture.future(() -> waitForLatchAndFail(allRunning, new Exception("fail"))),
                    (string1, string2) -> string2 + string1);

        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void reduce() throws ExecutionException, InterruptedException {
        var values = List.of("a", "b", "c");
        CountDownLatch allRunning = new CountDownLatch(values.size());

        var futures =
            values.stream()
                .map(s -> VFuture.future(() -> waitForLatchAndReturn(allRunning, s)));

        var reduced = VFuture.reduce(futures)
            .map(Stream::toList);
        assertEquals(values, reduced.get());
    }

    @Test
    @Timeout(1)
    public void foldToStream() throws ExecutionException, InterruptedException {
        var values = List.of("a", "b", "c", "d", "e");
        CountDownLatch allRunning = new CountDownLatch(values.size());

        var futures =
            values.stream()
                .map(s -> VFuture.future(() -> waitForLatchAndReturn(allRunning, s)));

        var folded = VFuture.foldLeft(
                futures,
                VFuture.succeeded(Stream.empty()),
                (stream, string) -> concat(stream, Stream.of(string)))
            .map(Stream::toList);
        assertEquals(values, folded.get());
    }

    @Test
    @Timeout(1)
    public void foldToSum() throws ExecutionException, InterruptedException {
        var values = List.of(1, 2, 3, 4, 5, 6);
        CountDownLatch allRunning = new CountDownLatch(values.size());

        var futures =
            values.stream()
                .map(k -> VFuture.future(() -> waitForLatchAndReturn(allRunning, k)));

        var folded = VFuture.foldLeft(
            futures,
            VFuture.succeeded(0),
            Integer::sum);
        assertEquals(values.stream().reduce(Integer::sum), Optional.of(folded.get()));
    }

    @Test
    @Timeout(1)
    public void foldToWithException() {
        var values = List.of(1, 2, 3, 4, 5, 6);
        CountDownLatch allRunning = new CountDownLatch(values.size());

        var futures =
            values.stream()
                .map(k -> VFuture.future(() -> waitForLatchAndReturn(allRunning, k)));

        futures = Stream.concat(futures, Stream.of(VFuture.failed(new RuntimeException("failed"))));

        var folded = VFuture.foldLeft(
            futures,
            VFuture.succeeded(0),
            Integer::sum);

        var ex = assertThrows(ExecutionException.class, folded::get);
        assertEquals("failed", ex.getCause().getMessage());
        assertTrue(folded.isFailed());
    }

    @Test
    @Timeout(1)
    public void reduceWithFailure() {
        var values = List.of("a", "b", "c");
        CountDownLatch allRunning = new CountDownLatch(values.size() + 1);

        var succeedingFutures =
            values.stream()
                .map(s -> VFuture.future(() -> waitForLatchAndReturn(allRunning, s)));

        var failingFuture = VFuture.future(() -> waitForLatchAndFail(allRunning, new Exception("fail")));

        var futures = concat(succeedingFutures, Stream.of(failingFuture));

        var reduced = VFuture.reduce(futures);

        assertThrows(ExecutionException.class, reduced::get);
    }

    @Test
    @Timeout(1)
    public void onSuccessWithSucceeded() throws InterruptedException, ExecutionException {
        var condition = new Promise<String>();
        VFuture<String> future = VFuture.succeeded("success");

        future.onSuccess(condition::succeed);
        future.onFail(__ -> Assertions.fail());

        assertTrue(future.isCompleted());
        assertEquals("success", future.get());
        assertEquals("success", condition.call());
    }

    @Test
    @Timeout(1)
    public void onSuccessWithCallable() throws ExecutionException, InterruptedException {
        var condition = new Promise<String>();
        var promise = new Promise<String>();
        VFuture<String> future = VFuture.future(promise);

        future.onSuccess(condition::succeed);
        future.onFail(__ -> Assertions.fail());

        assertFalse(future.isCompleted());
        assertFalse(future.isFailed());
        assertFalse(future.isSucceeded());

        promise.succeed("success");
        assertEquals("success", condition.call());
        assertEquals("success", future.get());
        assertTrue(future.isCompleted());
        assertFalse(future.isFailed());
        assertTrue(future.isSucceeded());
    }

    @Test
    @Timeout(1)
    public void onFailWithFailed() {
        var condition = new Promise<Void>();
        VFuture<String> future = VFuture.failed(new Exception("fail"));

        future.onFail(condition::fail);
        future.onSuccess(__ -> Assertions.fail());

        assertTrue(future.isCompleted());
        assertThrows(ExecutionException.class, future::get);
        assertThrows(ExecutionException.class, condition::call);
    }

    @Test
    @Timeout(1)
    public void onFailWithCallable() {
        var condition = new Promise<Void>();
        var promise = new Promise<String>();
        VFuture<String> future = VFuture.future(promise);

        future.onFail(condition::fail);
        future.onSuccess(__ -> Assertions.fail());

        assertFalse(future.isCompleted());
        assertFalse(future.isFailed());
        assertFalse(future.isSucceeded());

        promise.fail(new Exception("fail"));
        assertThrows(ExecutionException.class, future::get);
        assertTrue(future.isCompleted());
        assertThrows(ExecutionException.class, condition::call);
    }

    @Test
    @Timeout(1)
    public void succeed() throws ExecutionException, InterruptedException {
        var promise = new Promise<String>();
        var future = VFuture.future(promise);

        assertFalse(future.isCompleted());
        assertEquals(Optional.empty(), future.get(Duration.ofMillis(100)));

        promise.succeed("x");

        assertEquals("x", future.get());
        assertTrue(future.isCompleted());

        promise.succeed("y");
        assertEquals("x", future.get());

        promise.fail(new Exception());
        assertEquals("x", future.get());
    }

    @Test
    @Timeout(1)
    public void fail() throws ExecutionException, InterruptedException {
        var promise = new Promise<String>();
        var future = VFuture.future(promise);

        assertFalse(future.isCompleted());
        assertEquals(Optional.empty(), future.get(Duration.ofMillis(100)));

        promise.fail(new Exception());

        assertThrows(ExecutionException.class, future::get);
        assertTrue(future.isCompleted());

        promise.succeed("y");
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @Timeout(1)
    public void firstOfNone() {
        var result = VFuture.first(Stream.empty());

        assertFalse(result.isCompleted());
    }

    @Test
    @Timeout(1)
    public void firstOfMany() throws ExecutionException, InterruptedException {
        var future1 = VFuture.future(new Promise<String>());
        var promise2 = new Promise<String>();
        var future2 = VFuture.future(promise2);
        var future3 = VFuture.future(new Promise<String>());

        var result = VFuture.first(Stream.of(future1, future2, future3));

        assertEquals(Optional.empty(), result.get(Duration.ofMillis(100)));
        assertFalse(result.isCompleted());

        promise2.succeed("f2");

        assertEquals("f2", result.get());
        assertTrue(result.isCompleted());
    }

    @Test
    @Timeout(1)
    public void firstOfManyFailing() throws ExecutionException, InterruptedException {
        var future1 = VFuture.future(new Promise<String>());
        var promise2 = new Promise<String>();
        var future2 = VFuture.future(promise2);
        var future3 = VFuture.future(new Promise<String>());

        var result = VFuture.first(Stream.of(future1, future2, future3));

        assertEquals(Optional.empty(), result.get(Duration.ofMillis(100)));
        assertFalse(result.isCompleted());

        promise2.fail(new Exception("fail"));

        assertThrows(ExecutionException.class, result::get);
        assertTrue(result.isCompleted());
    }

    @Test
    @Timeout(1)
    public void collectEmptyStream() {
        var futures = Stream.<VFuture<String>>empty();

        var collected = VFuture.collect(futures);

        assertTrue(collected.isEmpty());
    }

    @Test
    @Timeout(1)
    public void collect() throws ExecutionException, InterruptedException {
        var p1 = new Promise<String>();
        var f1 = VFuture.future(p1);
        var p2 = new Promise<String>();
        var f2 = VFuture.future(p2);
        var p3 = new Promise<String>();
        var f3 = VFuture.future(p3);
        var futures = Stream.of(f1, f2, f3);

        var collected = VFuture.collect(futures);

        assertNull(collected.poll(10, MILLISECONDS));

        p2.succeed("f2");
        var r2 = collected.poll(10, MILLISECONDS);
        assertNotNull(r2);
        assertTrue(r2.isCompleted());
        assertEquals("f2", r2.get());

        p1.succeed("f1");
        var r1 = collected.poll(10, MILLISECONDS);
        assertNotNull(r1);
        assertTrue(r1.isCompleted());
        assertEquals("f1", r1.get());

        assertNull(collected.poll(10, MILLISECONDS));

        p3.succeed("f3");
        var r3 = collected.poll(10, MILLISECONDS);
        assertNotNull(r3);
        assertTrue(r3.isCompleted());
        assertEquals("f3", r3.get());

        assertNull(collected.poll(10, MILLISECONDS));
    }

    @Test
    @Timeout(1)
    public void collectAsync() throws ExecutionException, InterruptedException {
        var p1 = new Promise<String>();
        var f1 = VFuture.future(p1);
        var p2 = new Promise<String>();
        var f2 = VFuture.future(p2);
        var p3 = new Promise<String>();
        var f3 = VFuture.future(p3);
        var futures = Stream.of(f1, f2, f3);

        var collected = VFuture.collect(futures);

        var futureList = VFuture.future(() ->
                Stream.of(collected.take(), collected.take(), collected.take()))
            .andThen(VFuture::reduce)
            .map(Stream::toList);

        assertFalse(futureList.isCompleted());

        p2.succeed("f2");
        f2.get();
        assertFalse(futureList.isCompleted());

        p1.succeed("f1");
        f1.get();
        assertFalse(futureList.isCompleted());

        p3.succeed("f3");
        f3.get();

        assertEquals(List.of("f2", "f1", "f3"), futureList.get());
    }

    private <T> T waitForLatchAndReturn(CountDownLatch latch, T result) throws InterruptedException {
        latch.countDown();
        latch.await();
        return result;
    }

    private String waitForLatchAndFail(CountDownLatch latch, Exception failure) throws Exception {
        latch.countDown();
        latch.await();
        throw failure;
    }

    @Test
    public void manyConcurrentThreads() throws ExecutionException, InterruptedException {
        var N = 10000;
        var latch = new CountDownLatch(N);
        var futures = IntStream.range(0, N)
            .mapToObj(value ->
                VFuture.future(() -> {
                    latch.countDown();
                    latch.await();
                    return value;
            }));

        var sum = VFuture.reduce(futures)
            .map(stream -> stream.mapToInt(value -> value)
            .sum());

        assertEquals(N*(N - 1)/2, sum.get());
    }

}

