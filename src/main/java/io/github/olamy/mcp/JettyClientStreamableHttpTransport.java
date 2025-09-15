/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.olamy.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportStream;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.McpTransportStream;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.reactive.client.ReactiveRequest;
import org.eclipse.jetty.reactive.client.ReactiveResponse;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 *
 * Copyright 2025-2025 the original author or authors.
 * This class is not thread safe only one instance should be used per mcp client.
 * An implementation of the Streamable HTTP protocol as defined by the
 * <code>2025-03-26</code> version of the MCP specification.
 *
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">Streamable
 * HTTP transport specification</a>
 */
public class JettyClientStreamableHttpTransport implements McpClientTransport {

    private static final String NO_SESSION_ID = "[no_session_id]";

    private static final Logger logger = LoggerFactory.getLogger(JettyClientStreamableHttpTransport.class);

    private static final String MCP_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_03_26;

    private static final String DEFAULT_ENDPOINT = "/mcp";

    private static final String MESSAGE_EVENT_TYPE = "message";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final String endpoint;

    private final boolean openConnectionOnStartup;

    private final boolean resumableStreams;

    private final AtomicReference<DefaultMcpTransportSession> activeSession = new AtomicReference<>();

    private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler =
            new AtomicReference<>();

    private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

    private final RequestCustomiser requestCustomiser;

    public static final String HEADERS_CTX_KEY =
            JettyClientStreamableHttpTransport.class.getName() + ".HEADERS_CTX_KEY";

    private JettyClientStreamableHttpTransport(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String endpoint,
            boolean resumableStreams,
            boolean openConnectionOnStartup,
            RequestCustomiser requestCustomiser) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.resumableStreams = resumableStreams;
        this.openConnectionOnStartup = openConnectionOnStartup;
        this.requestCustomiser = requestCustomiser;
        this.activeSession.set(createTransportSession());
        if (!this.httpClient.isStarted()) {
            throw new IllegalStateException("HttpClient is not started");
        }
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26);
    }

    /**
     * Create a stateful builder for creating {@link JettyClientStreamableHttpTransport}
     * instances.
     * @param httpClient the {@link HttpClient} to use
     * @return a builder which will create an instance of
     * {@link JettyClientStreamableHttpTransport} once {@link Builder#build()} is called
     */
    public static Builder builder(HttpClient httpClient) {
        return new Builder(httpClient);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.deferContextual(ctx -> {
            this.handler.set(handler);
            if (openConnectionOnStartup) {
                logger.debug("open connection on startup");
                return this.reconnect(null).then();
            }
            return Mono.empty();
        });
    }

    private DefaultMcpTransportSession createTransportSession() {
        Function<String, Publisher<Void>> onClose = sessionId -> {
            if (sessionId == null) {
                return Mono.empty();
            }
            var request = this.httpClient
                    .newRequest(this.endpoint)
                    .method(HttpMethod.DELETE)
                    .headers(httpFields -> httpFields
                            .add(HttpHeaders.MCP_SESSION_ID, sessionId)
                            .add(HttpHeaders.PROTOCOL_VERSION, MCP_PROTOCOL_VERSION))
                    .onRequestFailure((request1, e) -> logger.warn("error when creating transport session", e));
            return Mono.from(ReactiveRequest.newBuilder(request)
                            .abortOnCancel(true)
                            .build()
                            .response())
                    .onErrorResume(e -> {
                        logger.warn("Got error when closing transport", e);
                        return Mono.empty();
                    })
                    .then();
        };

        return new DefaultMcpTransportSession(onClose);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        logger.debug("Exception handler registered");
        this.exceptionHandler.set(handler);
    }

    private void handleException(Throwable t) {
        logger.debug("Handling exception for session {}", sessionIdOrPlaceholder(this.activeSession.get()), t);
        if (t instanceof McpTransportSessionNotFoundException) {
            McpTransportSession<?> invalidSession = this.activeSession.getAndSet(createTransportSession());
            logger.warn("Server does not recognize session {}. Invalidating.", invalidSession.sessionId());
            invalidSession.close();
        }
        Consumer<Throwable> handler = this.exceptionHandler.get();
        if (handler != null) {
            handler.accept(t);
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            logger.debug("closeGracefully triggered");
            DefaultMcpTransportSession currentSession = this.activeSession.getAndSet(createTransportSession());
            if (currentSession != null) {
                return currentSession.closeGracefully();
            }
            return Mono.empty();
        });
    }

    private Mono<Disposable> reconnect(McpTransportStream<Disposable> stream) {
        return Mono.deferContextual(ctx -> {
            if (stream != null) {
                logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
            } else {
                logger.debug("Reconnecting with no prior stream");
            }
            // Here we attempt to initialize the client. In case the server supports SSE,
            // we will establish a long-running
            // session here and listen for messages. If it doesn't, that's ok, the server
            // is a simple, stateless one.
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = this.activeSession.get();

            var request = this.httpClient
                    .newRequest(this.endpoint)
                    .method(HttpMethod.GET)
                    .accept("text/event-stream")
                    .headers(httpFields -> {
                        httpFields.add(HttpHeaders.PROTOCOL_VERSION, MCP_PROTOCOL_VERSION);
                        transportSession.sessionId().ifPresent(id -> httpFields.add(HttpHeaders.MCP_SESSION_ID, id));
                        if (stream != null) {
                            stream.lastId().ifPresent(id -> httpFields.add(HttpHeaders.LAST_EVENT_ID, id));
                        }
                    })
                    .onRequestFailure((request1, e) -> logger.warn("Got error when reconnect", e));

            Optional<Map<String, String>> headers = ctx.getOrEmpty(HEADERS_CTX_KEY);
            headers.ifPresent(stringStringMap ->
                    stringStringMap.forEach((key, value) -> request.headers(httpFields -> httpFields.add(key, value))));

            Disposable connection = Flux.from(ReactiveRequest.newBuilder(request)
                            .abortOnCancel(true)
                            .build()
                            .response((reactiveResponse, chunkPublisher) -> {
                                if (isEventStream(reactiveResponse)) {
                                    return eventStream(stream, reactiveResponse, chunkPublisher);
                                } else if (isNotAllowed(reactiveResponse)) {
                                    logger.debug(
                                            "The server does not support SSE streams, using request-response mode.");
                                    return Flux.empty();
                                } else if (isNotFound(reactiveResponse)) {
                                    if (transportSession.sessionId().isPresent()) {
                                        String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
                                        return mcpSessionNotFoundError(sessionIdRepresentation);
                                    } else {
                                        return this.extractError(chunkPublisher, NO_SESSION_ID, reactiveResponse);
                                    }
                                } else {
                                    return Flux.error(new Exception());
                                }
                            }))
                    .flatMap(jsonrpcMessage -> this.handler.get().apply(Mono.just(jsonrpcMessage)))
                    .onErrorComplete(t -> {
                        this.handleException(t);
                        return true;
                    })
                    .doFinally(s -> {
                        Disposable ref = disposableRef.getAndSet(null);
                        if (ref != null) {
                            transportSession.removeConnection(ref);
                        }
                    })
                    .contextWrite(ctx)
                    .subscribe();
            disposableRef.set(connection);
            transportSession.addConnection(connection);
            return Mono.just(connection);
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.create(sink -> {
            logger.debug("Sending message {}", message);
            // Here we attempt to initialize the client. In case the server supports SSE, we will establish a
            // long-running session
            // here and listen for messages.  If it doesn't, nothing actually happens here, that's just the way it is...
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = this.activeSession.get();

            Request request;
            try {
                String body = objectMapper.writeValueAsString(message);
                request = httpClient
                        .newRequest(this.endpoint)
                        .method(HttpMethod.POST)
                        .accept("application/json", "text/event-stream")
                        .headers(httpFields -> {
                            httpFields
                                    .add(HttpHeaders.PROTOCOL_VERSION, MCP_PROTOCOL_VERSION)
                                    .add(HttpHeader.CONTENT_TYPE, "application/json");
                            transportSession
                                    .sessionId()
                                    .ifPresent(id -> httpFields.add(HttpHeaders.MCP_SESSION_ID, id));
                        })
                        .body(new StringRequestContent(body))
                        .onRequestFailure((request1, e) -> logger.warn("Got error when sending message", e));
                Optional<Map<String, String>> headers = sink.contextView().getOrEmpty(HEADERS_CTX_KEY);
                headers.ifPresent(stringStringMap -> stringStringMap.forEach(
                        (key, value) -> request.headers(httpFields -> httpFields.add(key, value))));
                request.body(new StringRequestContent(
                        requestCustomiser.customiseBody(body, request).get()));
            } catch (JsonProcessingException e) {
                sink.error(new RuntimeException(e));
                return;
            }

            Disposable connection = Flux.from(ReactiveRequest.newBuilder(request)
                            .abortOnCancel(true)
                            .build()
                            .response((reactiveResponse, chunkPublisher) -> {
                                String mcpSessionId =
                                        reactiveResponse.getHeaders().get(HttpHeaders.MCP_SESSION_ID);
                                if (mcpSessionId != null
                                        && transportSession.markInitialized(
                                                reactiveResponse.getHeaders().get(HttpHeaders.MCP_SESSION_ID))) {
                                    // Once we have a session, we try to open an async stream for
                                    // the server to send notifications and requests out-of-band.
                                    reconnect(null)
                                            .contextWrite(sink.contextView())
                                            .subscribe();
                                }
                                String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

                                // The spec mentions only ACCEPTED, but the existing SDKs can return
                                // 200 OK for notifications
                                if (reactiveResponse.getStatus() / 100 == 2) {
                                    Optional<String> contentType = Optional.ofNullable(
                                            reactiveResponse.getHeaders().get(HttpHeader.CONTENT_TYPE));
                                    // Existing SDKs consume notifications with no response body nor
                                    // content type
                                    if (contentType.isEmpty()) {
                                        logger.trace(
                                                "Message was successfully sent via POST for session {}",
                                                sessionRepresentation);
                                        // signal the caller that the message was successfully
                                        // delivered
                                        sink.success();
                                        // communicate to downstream there is no streamed data coming
                                        return Flux.empty();
                                    } else {

                                        if (contentType.get().startsWith("text/event-stream")) {
                                            logger.debug("Established SSE stream via POST");
                                            sink.success();
                                            return newEventStream(
                                                    reactiveResponse, sessionRepresentation, chunkPublisher);
                                        } else if (contentType.get().startsWith("application/json")) {
                                            logger.trace(
                                                    "Received response to POST for session {}", sessionRepresentation);
                                            sink.success();
                                            return directResponse(message, chunkPublisher);
                                        } else {
                                            logger.warn(
                                                    "Unknown media type {} returned for POST in session {}",
                                                    contentType,
                                                    sessionRepresentation);
                                            var e = new RuntimeException("Unknown media type returned: " + contentType);
                                            sink.error(e);
                                            return Flux.error(e);
                                        }
                                    }
                                } else {
                                    if (isNotFound(reactiveResponse) && !sessionRepresentation.equals(NO_SESSION_ID)) {
                                        return mcpSessionNotFoundError(sessionRepresentation);
                                    }
                                    return this.extractError(chunkPublisher, sessionRepresentation, reactiveResponse);
                                }
                            }))
                    .flatMap(jsonRpcMessage -> this.handler.get().apply(Mono.just(jsonRpcMessage)))
                    .onErrorComplete(t -> {
                        this.handleException(t);
                        sink.error(t);
                        return true;
                    })
                    .doFinally(s -> {
                        Disposable ref = disposableRef.getAndSet(null);
                        if (ref != null) {
                            transportSession.removeConnection(ref);
                        }
                    })
                    .contextWrite(sink.contextView())
                    .subscribe();
            disposableRef.set(connection);
            transportSession.addConnection(connection);
        });
    }

    private static Flux<McpSchema.JSONRPCMessage> mcpSessionNotFoundError(String sessionRepresentation) {
        logger.warn("Session {} was not found on the MCP server", sessionRepresentation);
        return Flux.error(new McpTransportSessionNotFoundException(sessionRepresentation));
    }

    private ByteBuffer concatBuffers(ByteBuffer b1, ByteBuffer b2) {
        ByteBuffer merged = ByteBuffer.allocate(b1.remaining() + b2.remaining());
        merged.put(b1).put(b2).flip();
        return merged;
    }

    private Flux<McpSchema.JSONRPCMessage> eventStream(
            McpTransportStream<Disposable> stream, ReactiveResponse response, Publisher<Content.Chunk> chunks) {
        McpTransportStream<Disposable> sessionStream =
                stream != null ? stream : new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
        logger.debug("Connected stream {}", sessionStream.streamId());

        Flux<ServerSentEvent> serverSentEventFlux = Flux.from(Flux.from(chunks)
                .map(Content.Chunk::getByteBuffer)
                .reduce(this::concatBuffers)
                .map(buf -> {
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    return parse(new String(bytes, StandardCharsets.UTF_8));
                }));
        var idWithMessages = serverSentEventFlux.map(this::parse);
        return Flux.from(sessionStream.consumeSseStream(idWithMessages));
    }

    private Flux<McpSchema.JSONRPCMessage> extractError(
            Publisher<Content.Chunk> chunks, String sessionRepresentation, ReactiveResponse response) {
        return Flux.from(chunks)
                .map(Content.Chunk::getByteBuffer)
                .reduce(this::concatBuffers)
                .flatMapMany(buf -> {
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    Exception toPropagate;
                    int status = response.getStatus();
                    try {
                        String responseMessage = new String(bytes);
                        if (logger.isDebugEnabled()) {
                            logger.debug("extractError, status {} responseMessage: {}", status, responseMessage);
                        }
                        McpSchema.JSONRPCResponse jsonRpcResponse =
                                objectMapper.readValue(responseMessage, McpSchema.JSONRPCResponse.class);
                        McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = jsonRpcResponse.error();
                        toPropagate = jsonRpcError != null
                                ? new McpError(jsonRpcError)
                                : new McpTransportException("Can't parse the jsonResponse " + jsonRpcResponse
                                        + ", json:" + responseMessage);
                        if (status / 100 == 4) {
                            if (!sessionRepresentation.equals(NO_SESSION_ID)) {
                                return Mono.error(
                                        new McpTransportSessionNotFoundException(sessionRepresentation, toPropagate));
                            }
                            return Mono.error(new McpTransportException(
                                    "Received " + status + " BAD REQUEST for session " + sessionRepresentation + ". "
                                            + toPropagate.getMessage(),
                                    toPropagate));
                        }
                    } catch (IOException ex) {
                        toPropagate = new McpTransportException("Sending request failed, " + ex.getMessage(), ex);
                    }
                    return Mono.error(toPropagate);
                });
    }

    private Flux<McpSchema.JSONRPCMessage> directResponse(
            McpSchema.JSONRPCMessage sentMessage, Publisher<Content.Chunk> chunks) {

        return Flux.from(chunks)
                .map(Content.Chunk::getByteBuffer)
                .reduce(this::concatBuffers)
                .mapNotNull(buf -> {
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    String message = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (sentMessage instanceof McpSchema.JSONRPCNotification && Utils.hasText(message)) {
                        logger.warn("Notification: {} received non-compliant response: {}", sentMessage, message);
                        return null;
                    } else {
                        try {
                            return List.of(McpSchema.deserializeJsonRpcMessage(objectMapper, message));
                        } catch (IOException e) {
                            throw new McpTransportException("Error parsing JSON-RPC message: " + message, e);
                        }
                    }
                })
                .flatMapIterable(Function.identity());
    }

    private Flux<McpSchema.JSONRPCMessage> newEventStream(
            ReactiveResponse response, String sessionRepresentation, Publisher<Content.Chunk> chunks) {
        McpTransportStream<Disposable> sessionStream =
                new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
        logger.debug(
                "Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(), sessionRepresentation);
        return eventStream(sessionStream, response, chunks);
    }

    private static boolean isNotFound(ReactiveResponse response) {
        return response.getResponse().getStatus() == HttpStatus.NOT_FOUND_404;
    }

    private static boolean isNotAllowed(ReactiveResponse response) {
        return response.getResponse().getStatus() == HttpStatus.METHOD_NOT_ALLOWED_405;
    }

    private static boolean isEventStream(ReactiveResponse response) {
        return response.getResponse().getStatus() / 100 == 2
                && response.getResponse().getHeaders().get(HttpHeader.CONTENT_TYPE) != null
                && response.getResponse()
                        .getHeaders()
                        .get(HttpHeader.CONTENT_TYPE)
                        .startsWith("text/event-stream");
    }

    private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
        return transportSession.sessionId().orElse(NO_SESSION_ID);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return this.objectMapper.convertValue(data, typeRef);
    }

    private Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>> parse(ServerSentEvent event) {
        if (MESSAGE_EVENT_TYPE.equals(event.getEvent())) {
            try {
                McpSchema.JSONRPCMessage message =
                        McpSchema.deserializeJsonRpcMessage(this.objectMapper, event.getData());
                return Tuples.of(Optional.ofNullable(event.getId()), List.of(message));
            } catch (IOException ioException) {
                throw new McpTransportException("Error parsing JSON-RPC message: " + event.getData(), ioException);
            }
        } else {
            logger.debug("Received SSE event with type: {}", event);
            return Tuples.of(Optional.empty(), List.of());
        }
    }

    protected ServerSentEvent parse(String eventRaw) {
        String[] lines = eventRaw.split("\n");
        ServerSentEvent serverSentEvent = new ServerSentEvent();
        StringBuilder data = null, comment = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                int length = line.length();
                if (length > 5) {
                    int index = (line.charAt(5) != ' ' ? 5 : 6);
                    if (length > index) {
                        data = (data != null ? data : new StringBuilder());
                        data.append(line, index, line.length());
                        data.append('\n');
                    }
                }
            }

            if (line.startsWith("id:")) {
                serverSentEvent.setId(line.substring(3).trim());
            } else if (line.startsWith("event:")) {
                serverSentEvent.setEvent(line.substring(6).trim());
            } else if (line.startsWith("retry:")) {
                serverSentEvent.setRetry(
                        Duration.ofMillis(Long.parseLong(line.substring(6).trim())));
            } else if (line.startsWith(":")) {
                comment = (comment != null ? comment : new StringBuilder());
                comment.append(line.substring(1).trim()).append('\n');
            }
        }
        serverSentEvent.setData(data != null ? data.toString() : null);
        serverSentEvent.setComment(comment != null ? comment.toString() : null);
        return serverSentEvent;
    }

    /**
     * Builder for {@link JettyClientStreamableHttpTransport}.
     */
    public static class Builder {

        private ObjectMapper objectMapper;

        private HttpClient httpClient;

        private String endpoint = DEFAULT_ENDPOINT;

        private boolean resumableStreams = true;

        private boolean openConnectionOnStartup = false;

        private RequestCustomiser requestCustomiser = RequestCustomiser.NOOP;

        private Supplier<McpTransportContext> contextProvider = () -> McpTransportContext.EMPTY;

        private Builder(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        }

        /**
         * Configure the {@link ObjectMapper} to use.
         * @param objectMapper instance to use
         * @return the builder instance
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "ObjectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * @param httpClient instance to use the http client to use must  have been started
         * @return the builder instance
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
            return this;
        }

        /**
         * Configure the endpoint to make HTTP requests against.
         * @param endpoint endpoint to use
         * @return the builder instance
         */
        public Builder endpoint(String endpoint) {
            Assert.hasText(endpoint, "endpoint must be a non-empty String");
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Configure whether to use the stream resumability feature by keeping track of
         * SSE event ids.
         * @param resumableStreams if {@code true} event ids will be tracked and upon
         * disconnection, the last seen id will be used upon reconnection as a header to
         * resume consuming messages.
         * @return the builder instance
         */
        public Builder resumableStreams(boolean resumableStreams) {
            this.resumableStreams = resumableStreams;
            return this;
        }

        /**
         * Configure whether the client should open an SSE connection upon startup. Not
         * all servers support this (although it is in theory possible with the current
         * specification), so use with caution. By default, this value is {@code false}.
         * @param openConnectionOnStartup if {@code true} the {@link #connect(Function)}
         * method call will try to open an SSE connection before sending any JSON-RPC
         * request
         * @return the builder instance
         */
        public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
            this.openConnectionOnStartup = openConnectionOnStartup;
            return this;
        }

        /**
         * Configure a customizer to modify the request body before sending it to the
         * server.
         * @param requestCustomiser the customizer to use, must not be {@code null}.
         * By default, no customization is applied.
         * @return the builder instance
         */
        public Builder requestCustomiser(RequestCustomiser requestCustomiser) {
            this.requestCustomiser = requestCustomiser;
            return this;
        }

        public Builder transportContextProvider(Supplier<McpTransportContext> contextProvider) {
            this.contextProvider = contextProvider;
            return this;
        }

        /**
         * Construct a fresh instance of {@link JettyClientStreamableHttpTransport} using
         * the current builder configuration.
         * @return a new instance of {@link JettyClientStreamableHttpTransport}
         */
        public JettyClientStreamableHttpTransport build() {
            ObjectMapper objectMapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();

            return new JettyClientStreamableHttpTransport(
                    objectMapper,
                    this.httpClient,
                    endpoint,
                    resumableStreams,
                    openConnectionOnStartup,
                    requestCustomiser);
        }
    }
}
