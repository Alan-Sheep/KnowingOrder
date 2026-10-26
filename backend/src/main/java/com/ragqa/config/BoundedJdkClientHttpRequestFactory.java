package com.ragqa.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 普通 JSON 请求使用的有界传输适配器：超时覆盖完整正文接收，响应按字节限额缓冲。
 *
 * <p>复用传入的 JDK 客户端，不创建额外的等待线程池；不适用于上传大文件或 SSE。
 * 可为单个请求绑定剩余预算，未绑定时仍使用固定传输时限；不负责业务重试或调度。</p>
 */
public final class BoundedJdkClientHttpRequestFactory implements ClientHttpRequestFactory {

    private static final Set<String> MANAGED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;
    private final long timeoutNanos;
    private final int maxResponseBytes;

    public BoundedJdkClientHttpRequestFactory(HttpClient client, Duration timeout, int maxResponseBytes) {
        this.client = Objects.requireNonNull(client, "HTTP 客户端不能为空");
        this.timeoutNanos = Objects.requireNonNull(timeout, "调用时限不能为空").toNanos();
        if (timeoutNanos <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("调用时限与响应大小上限必须大于零");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * 在 RestClient 的 httpRequest 回调中绑定当前任务的剩余预算，例如 deadline::remaining。
     * 预算只保存在本次请求上，不修改共享客户端、不发送特殊请求头，也不使用线程局部变量。
     * 必须传入本工厂创建的原始请求；不支持的包装请求明确拒绝，避免静默丢失总预算。
     */
    public static void useRemainingBudget(ClientHttpRequest request, Supplier<Duration> remainingBudget) {
        Objects.requireNonNull(remainingBudget, "剩余预算计算器不能为空");
        if (!(request instanceof BudgetedRequest bounded)) {
            throw new IllegalArgumentException("当前 HTTP 请求不支持任务预算绑定");
        }
        if (bounded.remainingBudget != null) {
            throw new IllegalStateException("同一 HTTP 请求不能重复绑定任务预算");
        }
        bounded.remainingBudget = remainingBudget;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new BudgetedRequest() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            protected OutputStream getBodyInternal(HttpHeaders headers) {
                return body;
            }

            @Override
            protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("外部服务调用已取消", new InterruptedException("调用线程已中断"));
                }
                long started = System.nanoTime();
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                headers.forEach((name, values) -> {
                    // 长度与连接级头部由 JDK 生成，避免与实际发送的字节数不一致。
                    if (!MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        values.forEach(value -> builder.header(name, value));
                    }
                });
                // 请求体转换和请求构建后再取余额；预算耗尽时不得发送请求。
                long allowed = limitToRemainingBudget(timeoutNanos - (System.nanoTime() - started));
                long sentAt = System.nanoTime();
                builder.timeout(Duration.ofNanos(allowed));
                CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(builder.build(),
                        info -> new LimitedBodySubscriber(maxResponseBytes));
                try {
                    long remaining = limitToRemainingBudget(allowed - (System.nanoTime() - sentAt));
                    // 该 Future 只在正文完整接收后完成，不能换成 ofInputStream。
                    HttpResponse<byte[]> completed = exchange.get(remaining, TimeUnit.NANOSECONDS);
                    limitToRemainingBudget(allowed - (System.nanoTime() - sentAt));
                    return response(completed);
                } catch (IOException exception) {
                    exchange.cancel(true);
                    throw exception;
                } catch (TimeoutException exception) {
                    exchange.cancel(true);
                    throw new IOException("外部服务响应超时", exception);
                } catch (InterruptedException exception) {
                    exchange.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new IOException("外部服务调用已取消", exception);
                } catch (ExecutionException | CancellationException exception) {
                    exchange.cancel(true);
                    Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
                    if (cause instanceof HttpTimeoutException) {
                        // JDK 计时器可能先于正文等待超时，统一分类且不传播上游异常原文。
                        throw new IOException("外部服务响应超时", new TimeoutException("外部服务响应超时"));
                    }
                    throw new IOException("外部服务调用失败", cause);
                } catch (RuntimeException exception) {
                    // 预算计算器异常也不能遗留在途调用。
                    exchange.cancel(true);
                    throw exception;
                }
            }
        };
    }

    /** 每个请求各有一个预算来源；传输开始后只能收紧本次已分配的时限。 */
    private abstract static class BudgetedRequest extends AbstractClientHttpRequest {
        private Supplier<Duration> remainingBudget;

        protected final long limitToRemainingBudget(long limit) throws IOException {
            if (remainingBudget != null) {
                Duration remaining = Objects.requireNonNull(remainingBudget.get(), "剩余预算不能为空");
                if (remaining.compareTo(Duration.ZERO) <= 0) {
                    limit = 0;
                } else if (remaining.compareTo(Duration.ofNanos(Math.max(0, limit))) < 0) {
                    // 先比较再转纳秒，超大外部预算也不能溢出或扩大传输上限。
                    limit = remaining.toNanos();
                }
            }
            if (limit <= 0) {
                throw new IOException("外部服务响应超时", new TimeoutException());
            }
            return limit;
        }
    }

    private static ClientHttpResponse response(HttpResponse<byte[]> response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach(headers::put);
        HttpHeaders immutableHeaders = HttpHeaders.readOnlyHttpHeaders(headers);
        InputStream body = new ByteArrayInputStream(response.body());
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                return HttpStatusCode.valueOf(response.statusCode());
            }

            @Override
            public String getStatusText() {
                HttpStatus status = HttpStatus.resolve(response.statusCode());
                return status == null ? "" : status.getReasonPhrase();
            }

            @Override
            public HttpHeaders getHeaders() {
                return immutableHeaders;
            }

            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public void close() {
                // 此时网络正文已接收完毕，后续只读取有大小限制的内存缓冲。
            }
        };
    }

    /** 在交给 JDK 缓冲前检查字节数，不信任 Content-Length，也覆盖分块传输。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maxBytes;
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

        private LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > maxBytes) {
                    subscription.cancel();
                    onError(new IOException("外部服务响应超过大小限制"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }
}
