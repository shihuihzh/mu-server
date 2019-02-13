package io.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * <p>An interface for sending Server-Sent Events (SSE) to a client with async callbacks.</p>
 * <p>If you aren't sure if you need async or not, use the {@link SsePublisher} interface instead as it is simpler.</p>
 * <p>The following example creates a publisher and publishes 10 messages to it from another thread:</p>
 * <pre><code>
 * server = httpsServer()
 *     .addHandler(Method.GET, "/streamer", (request, response, pathParams) -&gt; {
 *         SsePublisher ssePublisher = SsePublisher.start(request, response);
 *         new Thread(() -&gt; {
 *             try {
 *                 for (int i = 0; i &lt; 100; i++) {
 *                     ssePublisher.send("This is message " + i);
 *                     Thread.sleep(1000);
 *                 }
 *             } catch (Exception e) {
 *                 // the user has probably disconnected; stop publishing
 *             } finally {
 *                 ssePublisher.close();
 *             }
 *         }).start();
 *
 *     })
 *     .start();
 * </code></pre>
 *
 * @see SsePublisher
 */
public interface AsyncSsePublisher {

    /**
     * Sends a message (without an ID or event type)
     *
     * @param message The message to send
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message);

    /**
     * <p>Sends a message with an event type (without an ID).</p>
     * <p>Clients can use the event type to listen to different types of events, for example if the event type is <code>pricechange</code>
     * the the following JavaScript can be used:</p>
     * <pre><code>
     *     var source = new EventSource('/streamer');
     *     source.addEventListener('pricechange', e =&gt; console.log(e.data));
     * </code></pre>
     *
     * @param message The message to send
     * @param event   An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message, String event);

    /**
     * <p>Sends a message with an event type and ID.</p>
     * <p>Clients can use the event type to listen to different types of events, for example if the event type is <code>pricechange</code>
     * the the following JavaScript can be used:</p>
     * <pre><code>
     *     var source = new EventSource('/streamer');
     *     source.addEventListener('pricechange', e =&gt; console.log(e.data));
     * </code></pre>
     *
     * @param message The message to send
     * @param event   An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @param eventID An identifier for the message. If set, and the browser reconnects, then the last event ID will be
     *                sent by the browser in the <code>Last-Event-ID</code> request header.
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message, String event, String eventID);

    /**
     * <p>Stops the event stream.</p>
     * <p><strong>Warning:</strong> most clients will reconnect several seconds after this message is called. To prevent that
     * happening, close the stream from the client or on the next request return a <code>204 No Content</code> to the client.</p>
     */
    void close();

    /**
     * Sends a comment to the client. Clients will ignore this, however it can be used as a way to keep the connection alive.
     *
     * @param comment A single-line string to send as a comment.
     * @return completion stage that completes when the comment has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> sendComment(String comment);

    /**
     * <p>Sends a message to the client instructing it to reconnect after the given time period in case of any disconnection
     * (including calling {@link #close()} from the server). A common default (controlled by the client) is several seconds.</p>
     * <p>Note: clients could ignore this value.</p>
     *
     * @param timeToWait The time the client should wait before attempting to reconnect in case of any disconnection.
     * @param unit       The unit of time.
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> setClientReconnectTime(long timeToWait, TimeUnit unit);

    /**
     * <p>Creates a new Server-Sent Events publisher. This is designed by be called from within a MuHandler.</p>
     * <p>This will set the content type of the response to <code>text/event-stream</code> and disable caching.</p>
     * <p>The request will also switch to async mode, which means you can use the returned publisher in another thread.</p>
     * <p><strong>IMPORTANT:</strong> The {@link #close()} method must be called when publishing is complete.</p>
     *
     * @param request  The current MuRequest
     * @param response The current MuResponse
     * @return Returns a publisher that can be used to send messages to the client.
     */
    static AsyncSsePublisher start(MuRequest request, MuResponse response) {
        response.contentType(ContentTypes.TEXT_EVENT_STREAM);
        response.headers().set(HeaderNames.CACHE_CONTROL, "no-cache, no-transform");
        return new AsyncSsePublisherImpl(request.handleAsync());
    }
}

class AsyncSsePublisherImpl implements AsyncSsePublisher {

    private final AsyncHandle asyncHandle;

    AsyncSsePublisherImpl(AsyncHandle asyncHandle) {
        this.asyncHandle = asyncHandle;
    }

    @Override
    public CompletionStage<?> send(String message) {
        return send(message, null, null);
    }

    @Override
    public CompletionStage<?> send(String message, String event) {
        return send(message, event, null);
    }

    @Override
    public CompletionStage<?> send(String message, String event, String eventID) {
        return write(SsePublisherImpl.dataText(message, event, eventID));
    }

    @Override
    public CompletionStage<?> sendComment(String comment) {
        return write(SsePublisherImpl.commentText(comment));
    }

    @Override
    public CompletionStage<?> setClientReconnectTime(long timeToWait, TimeUnit unit) {
        return write(SsePublisherImpl.clientReconnectText(timeToWait, unit));
    }

    private CompletionStage<?> write(String text) {
        CompletableFuture<?> stage = new CompletableFuture<>();
        asyncHandle.write(ByteBuffer.wrap(text.getBytes(UTF_8)), new WriteCallback() {
            @Override
            public void onFailure(Throwable reason) {
                stage.completeExceptionally(reason);
            }

            @Override
            public void onSuccess() {
                stage.complete(null);
            }
        });
        return stage;
    }

    @Override
    public void close() {
        asyncHandle.complete();
    }
}
