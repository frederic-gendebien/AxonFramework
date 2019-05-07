/*
 * Copyright (c) 2010-2019. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector.query;

import com.google.protobuf.ByteString;
import io.axoniq.axonserver.grpc.SerializedObject;
import io.axoniq.axonserver.grpc.query.QueryProviderInbound;
import io.axoniq.axonserver.grpc.query.QueryProviderOutbound;
import io.axoniq.axonserver.grpc.query.QueryRequest;
import io.grpc.stub.StreamObserver;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.ErrorCode;
import org.axonframework.common.Registration;
import org.axonframework.messaging.MetaData;
import org.axonframework.messaging.responsetypes.InstanceResponseType;
import org.axonframework.queryhandling.GenericQueryMessage;
import org.axonframework.queryhandling.GenericSubscriptionQueryMessage;
import org.axonframework.queryhandling.QueryExecutionException;
import org.axonframework.queryhandling.QueryMessage;
import org.axonframework.queryhandling.QueryResponseMessage;
import org.axonframework.queryhandling.SimpleQueryBus;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.junit.*;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.axonserver.connector.utils.AssertUtils.assertWithin;
import static org.axonframework.common.ObjectUtils.getOrDefault;
import static org.axonframework.messaging.responsetypes.ResponseTypes.instanceOf;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * @author Marc Gathier
 */
public class AxonServerQueryBusTest {

    private DummyMessagePlatformServer dummyMessagePlatformServer;
    private AxonServerConfiguration conf;
    private SimpleQueryBus localSegment;
    private AxonServerConnectionManager axonServerConnectionManager;
    private AtomicReference<StreamObserver<QueryProviderInbound>> inboundStreamObserverRef;
    private XStreamSerializer ser;

    private AxonServerQueryBus testSubject;

    @Before
    public void setup() throws Exception {
        conf = new AxonServerConfiguration();
        conf.setServers("localhost:4343");
        conf.setClientId("JUnit");
        conf.setComponentName("JUnit");
        conf.setInitialNrOfPermits(100);
        conf.setNewPermitsThreshold(10);
        conf.setNrOfNewPermits(1000);
        localSegment = SimpleQueryBus.builder().build();
        ser = XStreamSerializer.builder().build();
        testSubject = new AxonServerQueryBus(
                new AxonServerConnectionManager(conf), conf, localSegment.queryUpdateEmitter(), localSegment, ser, ser,
                QueryPriorityCalculator.defaultQueryPriorityCalculator()
        );
        dummyMessagePlatformServer = new DummyMessagePlatformServer(4343);
        dummyMessagePlatformServer.start();
        axonServerConnectionManager = mock(AxonServerConnectionManager.class);
        inboundStreamObserverRef = new AtomicReference<>();
        doAnswer(invocationOnMock -> {
            inboundStreamObserverRef.set(invocationOnMock.getArgument(0));
            return new StreamObserver<QueryProviderOutbound>() {
                @Override
                public void onNext(QueryProviderOutbound commandProviderOutbound) {
                    System.out.println(commandProviderOutbound);
                }

                @Override
                public void onError(Throwable throwable) {
                    System.out.println("Error:" + throwable);
                }

                @Override
                public void onCompleted() {
                    System.out.println("Completed");
                }
            };
        }).when(axonServerConnectionManager).getQueryStream(any(), any());
    }

    @After
    public void tearDown() {
        dummyMessagePlatformServer.stop();
    }

    @Test
    public void subscribe() throws Exception {
        Registration response = testSubject.subscribe("testQuery", String.class, q -> "test");
        Thread.sleep(1000);
        assertWithin(1000,
                     TimeUnit.MILLISECONDS,
                     () -> assertNotNull(dummyMessagePlatformServer
                                                 .subscriptions("testQuery", String.class.getName())));

        response.cancel();
        assertWithin(2000,
                     TimeUnit.MILLISECONDS,
                     () -> assertNull(dummyMessagePlatformServer.subscriptions("testQuery", String.class.getName())));
    }

    @Test
    public void query() throws Exception {
        QueryMessage<String, String> queryMessage = new GenericQueryMessage<>("Hello, World",
                                                                              instanceOf(String.class));

        assertEquals("test", testSubject.query(queryMessage).get().getPayload());
    }

    @Test
    public void queryWhenQueryServiceStubFails() {
        RuntimeException expected = new RuntimeException("oops");
        testSubject = spy(testSubject);
        when(testSubject.queryService()).thenThrow(expected);

        QueryMessage<String, String> queryMessage = new GenericQueryMessage<>("Hello, World", instanceOf(String.class));
        CompletableFuture<QueryResponseMessage<String>> result = testSubject.query(queryMessage);
        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());

        try {
            result.get();
            fail("Expected an exception here");
        } catch (Exception actual) {
            assertTrue(actual.getCause() instanceof AxonServerQueryDispatchException);
            AxonServerQueryDispatchException queryDispatchException = (AxonServerQueryDispatchException) actual
                    .getCause();
            assertEquals(ErrorCode.QUERY_DISPATCH_ERROR.errorCode(), queryDispatchException.code());
        }
    }

    @Test
    public void testQueryReportsCorrectException() throws ExecutionException, InterruptedException {
        QueryMessage<String, String> queryMessage = new GenericQueryMessage<>("Hello, World", instanceOf(String.class))
                .andMetaData(Collections.singletonMap("errorCode", ErrorCode.QUERY_EXECUTION_ERROR.errorCode()));
        CompletableFuture<QueryResponseMessage<String>> result = testSubject.query(queryMessage);
        assertNotNull(result.get());
        assertFalse(result.isCompletedExceptionally());

        assertTrue(result.get().isExceptional());
        Throwable actual = result.get().exceptionResult();
        assertTrue(actual instanceof QueryExecutionException);
        AxonServerRemoteQueryHandlingException queryDispatchException = (AxonServerRemoteQueryHandlingException) actual
                .getCause();
        assertEquals(ErrorCode.QUERY_EXECUTION_ERROR.errorCode(), queryDispatchException.getErrorCode());
    }

    @Test
    public void processQuery() {
        AxonServerQueryBus queryBus2 = new AxonServerQueryBus(
                axonServerConnectionManager, conf, localSegment.queryUpdateEmitter(), localSegment, ser, ser,
                QueryPriorityCalculator.defaultQueryPriorityCalculator()
        );
        Registration response = queryBus2.subscribe("testQuery", String.class, q -> "test: " + q.getPayloadType());

        QueryProviderInbound inboundMessage = testQueryMessage();
        inboundStreamObserverRef.get().onNext(inboundMessage);

        response.close();
    }

    @Test
    public void scatterGather() {
        QueryMessage<String, String> queryMessage = new GenericQueryMessage<>("Hello, World",
                                                                              instanceOf(String.class))
                .andMetaData(MetaData.with("repeat", 10).and("interval", 10));

        assertEquals(10, testSubject.scatterGather(queryMessage, 12, TimeUnit.SECONDS).count());
    }

    @Test
    public void scatterGatherTimeout() {
        QueryMessage<String, String> queryMessage = new GenericQueryMessage<>("Hello, World",
                                                                              instanceOf(String.class))
                .andMetaData(MetaData.with("repeat", 10).and("interval", 100));

        assertTrue(8 > testSubject.scatterGather(queryMessage, 550, TimeUnit.MILLISECONDS).count());
    }

    @Test
    public void testSubscriptionQueryIsHandledByDispatchInterceptors() {
        AtomicInteger counter = new AtomicInteger(0);

        // Add a dispatch interceptor which increase the counter, to check whether it was called during a sub.query
        testSubject.registerDispatchInterceptor(messages -> {
            counter.incrementAndGet();
            return (i, m) -> m;
        });

        testSubject.subscriptionQuery(new GenericSubscriptionQueryMessage<>(
                "query-payload", instanceOf(String.class), instanceOf(String.class)
        ));

        assertEquals(1, counter.get());
    }

    @Test
    public void dispatchInterceptor() {
        List<Object> results = new LinkedList<>();
        testSubject.registerDispatchInterceptor(messages -> (a, b) -> {
            results.add(b.getPayload());
            return b;
        });
        testSubject.query(new GenericQueryMessage<>("payload", new InstanceResponseType<>(String.class)));
        assertEquals("payload", results.get(0));
        assertEquals(1, results.size());
    }

    @Test
    public void handlerInterceptor() {
        SimpleQueryBus localSegment = SimpleQueryBus.builder().build();
        AxonServerQueryBus bus = new AxonServerQueryBus(
                axonServerConnectionManager, conf, localSegment.queryUpdateEmitter(), localSegment, ser, ser,
                QueryPriorityCalculator.defaultQueryPriorityCalculator()
        );
        bus.subscribe("testQuery", String.class, q -> "test: " + q.getPayloadType());
        List<Object> results = new LinkedList<>();
        bus.registerHandlerInterceptor((unitOfWork, interceptorChain) -> {
            results.add("Interceptor executed");
            return interceptorChain.proceed();
        });
        QueryProviderInbound inboundMessage = testQueryMessage();
        inboundStreamObserverRef.get().onNext(inboundMessage);
        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(1, results.size()));
        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals("Interceptor executed", results.get(0)));
    }

    @Test
    public void reconnectAfterConnectionLost() throws InterruptedException {
        testSubject.subscribe("testQuery", String.class, q -> "test");
        Thread.sleep(50);
        assertNotNull(dummyMessagePlatformServer.subscriptions("testQuery", String.class.getName()));
        dummyMessagePlatformServer.onError("testQuery", String.class.getName());
        Thread.sleep(200);
        assertNotNull(dummyMessagePlatformServer.subscriptions("testQuery", String.class.getName()));
    }

    private QueryProviderInbound testQueryMessage() {
        org.axonframework.serialization.SerializedObject<byte[]> response =
                ser.serialize(instanceOf(String.class), byte[].class);
        return QueryProviderInbound.newBuilder().setQuery(
                QueryRequest.newBuilder().setQuery("testQuery")
                            .setResponseType(SerializedObject.newBuilder().setData(
                                    ByteString.copyFrom(response.getData()))
                                                             .setType(response.getType().getName())
                                                             .setRevision(getOrDefault(response.getType().getRevision(),
                                                                                       "")))
                            .setPayload(SerializedObject.newBuilder()
                                                        .setData(ByteString.copyFromUtf8("<string>Hello</string>"))
                                                        .setType(String.class.getName()))
        ).build();
    }
}