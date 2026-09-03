# Why we named it as [`MediaProcessingResultSink`](../media-processing/src/main/java/com/hello/mediaprocessing/service/MediaProcessingResultSink.java)

`ResultSink` is an **outbound port**: a place the worker **pushes finished results into**, without knowing what happens next.

The handler already owns transcode/metadata work. It must not know whether the next step is “log it,” “POST to chat-app-backend,” or “write a queue.” So it only calls `resultSink.accept(result)`. Today that is `LoggingMediaProcessingResultSink`; Phase 7 should swap in a real callback implementation without changing `MediaProcessingJobHandler`.

That is the same idea as:

- **Ports and adapters** — handler = core; sink = output adapter
- **Strategy** — swap the destination
- **Java `Consumer<T>`** — one method, `accept`

“Sink” is the usual name for that (vs `Source`, which you pull from). `MediaProcessingResultSink` = “drain for `MediaProcessingResult`.” Names like `ResultPublisher` or `ResultReporter` would also work; sink stresses “output hole,” not “we already have a message bus.”

**Why only `accept`?** The contract is one thing: take this completed result. Extra methods (`flush`, `close`, `reportFailure`) would couple the handler to a richer API before Phase 7 exists. Failures are already in `MediaProcessingResult.status`. A single `void accept(...)` matches `Consumer` and keeps tests to `NoopResultSink` / `CapturingResultSink`.

So: not a special framework type — a one-method **output port** named like a sink, waiting for the real backend callback.
