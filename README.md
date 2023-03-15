# A Virtual Future for Java
An instance of a `VFuture` is a handle to the result of an asynchronous computation.
It captures the effects of latency and failure: it is `completed` once the computation
finishes (either successfully or with a failure). Additionally, it `succeeded` or
`failed` depending on the outcome of the computation. When completed, it provides 
access to the outcome of the computation.

Internally each `VFuture` instance is backed by a virtual thread that is responsible for producing the future's result.

## Creation and Completion
A newly created `VFuture` might complete - either successfully or failed - at some future point in time. Once completed its result becomes available, either from a callback or from one of its accessors.

Use `VFuture#succeeded(T)` and `VFuture#failed(Throwable)` to create instances that are already completed, the first on successfully and the second one failed.

Use `VFuture#future(Callable<T>)` to create an instance that executes the passed `Callable` asynchronously and completes once the `Callable` finishes executing. If the `Callable` throws an exception the `VFuture` fails with that exception, otherwise it succeeds with the value returned from the `Callable`.

Pass a `Promise<T>` to `VFuture#future(Callable<T>)` to create a new instance that can be completed manually by either calling `Promise#succeed(T)` or `Promise#fail(Throwable)`.

Register a callback with `VFuture#onSuccess(Consumer<VFuture<T>>)`,
`VFuture#onComplete(Consumer<T>)` or `VFuture#onFail(Consumer<Throwable>)` to get notified on completion.

Use one of the polling methods `VFuture#get()` or `VFuture#get(Duration)` to retrieve the result of a `VFuture`. These methods block either until completion or a timeout expired.

Use `VFuture#isCompleted()`, `VFuture#isSucceeded()` or `VFuture#isFailed()` to determine the completion status of a `VFuture`.

## Transforming, Sequencing and Recovering
`VFuture` offers various ways to lift functions into the future and for sequencing multiple futures.

Use `VFuture#map(Function<T, R>)` to convert a `VFuture<T>` into a `VFuture<R>`. E.g. lift `Integer#parseInt` into a `VFuture<String>` to convert it into a `VFuture<Integer>`:

    VFuture<String> future = ...
    VFuture>Integer> intFuture = future.map(Integer::parseInt);

Use `VFuture#andThen(Function<T, VFuture<R>>)` to sequence multiple `VFuture`s one after each other, where the next one depends on the result of the previous one. E.g. to retrieve a product and then a rating for that product to create a product review:

    VFuture<String> productReview = VFuture.future(
        API::getProduct)
            .andThen(product -> VFuture.future(() ->
        API.getRating(product))
            .map(rating ->
        newProductReview(product, rating)));

Use `VFuture#andAlso(VFuture<Function<T, R>>)` or `VFuture#andAlso(VFuture<S>, BiFunction<T, S, R>)` when a `VFuture` instance does not depend on the result of previous ones. This allows parallel execution of the asynchronous tasks. E.g. to calculate the speed from the result of two futures, one for a speed and the other a duration:

    VFuture<Integer> duration = VFuture.future(
        API::getSpeed)
            .andAlso(VFuture.future(
        API::getDistance)
            .map(distance -> speed ->
        distance / speed));

or with the `andAlso` overload taking a `BiFunction` for combining the individual results:

    VFuture<Integer> duration = VFuture.future(
        API::getSpeed)
            .andAlso(VFuture.future(
        API::getDistance),
            (distance, speed) ->
        distance / speed);

Use one of the `VFuture#recover...()` methods to recover a failed future into a succeeded one:
 - `recover(Function<Throwable,T>)` corresponds to `VFuture#map(Function<T, R>)` for the failure case.
 - `VFuture#recover(T)` is a shortcut for `future.recover(ignore --> t)`.
 - `VFuture#recoverWith(Function<Throwable, VFuture<T>>)` corresponds to `VFuture#andThen(Function<T, VFuture<R>>)` for the failure case.

## Combining, Reducing, Folding and Collecting
Use `VFuture#first(Stream<VFuture<T>>)` to find the first completing instance in a stream of `VFuture`s.

Use `VFuture#collect(Stream<VFuture<T>>)` to collect a stream of `VFuture`s into a blocking queue, ordered by the order of completion of the individual instances on the stream.

Use `VFuture#reduce(Stream<VFuture<T>>)` to reduce a stream of `VFuture`s into a `VFuture<Stream<T>>`. Reduction runs in parallel and returned `VFuture` fails immediately once one of the instances in the stream fail and succeeds otherwise.

Use `VFuture#foldLeft(Stream<VFuture<T>>, VFuture<R>, BiFunction<R, T, R>)` to fold a stream of `VFuture`s into a single `VFuture` by repeatedly applying a `BiFunction` to the individual futures.