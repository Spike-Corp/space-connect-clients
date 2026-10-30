package com.limelight.utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/**
 * Cancels only this operation's calls, including a response body still being read.
 * The OkHttp dispatcher/connection pool may be shared with unrelated operations.
 */
public final class RequestScope implements AutoCloseable {
    private final Set<Call> calls = new HashSet<>();
    private boolean cancelled;

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Request cancelled");
        }
    }

    public Response execute(OkHttpClient client, Request request) throws IOException {
        final Call call = client.newCall(request);
        synchronized (this) {
            checkCancelled();
            calls.add(call);
        }
        try {
            Response response = call.execute();
            ResponseBody body = response.body();
            if (body == null) {
                remove(call);
                return response;
            }
            BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        remove(call);
                    }
                }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() { return body.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        } catch (IOException | RuntimeException e) {
            remove(call);
            throw e;
        }
    }

    private synchronized void remove(Call call) {
        calls.remove(call);
    }

    @Override
    public void close() {
        Call[] pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = calls.toArray(new Call[0]);
            calls.clear();
        }
        for (Call call : pending) {
            call.cancel();
        }
    }
}
