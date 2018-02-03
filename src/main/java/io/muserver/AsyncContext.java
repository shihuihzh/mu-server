package io.muserver;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;

import java.util.concurrent.Callable;

public class AsyncContext {
	public final MuRequest request;
	public final MuResponse response;
    private final EventExecutor executor;

    AsyncContext(MuRequest request, MuResponse response, EventExecutor executor) {
		this.request = request;
		this.response = response;
        this.executor = executor;
    }

    public io.netty.util.concurrent.Future<Boolean> submit(Callable<Boolean> task) {
        return executor.submit(task);
    }
    public Future<Boolean> wasHandled() {
        return executor.newSucceededFuture(true);
    }
    public Future<Boolean> wasNotHandled() {
        return executor.newSucceededFuture(false);
    }

	public io.netty.util.concurrent.Future<Void> complete() {
		return ((NettyResponseAdaptor)response).complete();
	}
}
