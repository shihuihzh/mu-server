package io.muserver;


import io.netty.util.concurrent.Future;

import java.nio.ByteBuffer;

public interface AsyncMuHandler {

	Future<Boolean> onHeaders(AsyncContext ctx, Headers headers) throws Exception;

	void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception;

	void onRequestComplete(AsyncContext ctx);


}
