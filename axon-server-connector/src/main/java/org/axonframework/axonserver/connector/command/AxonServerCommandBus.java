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

package org.axonframework.axonserver.connector.command;

import io.axoniq.axonserver.grpc.ErrorMessage;
import io.axoniq.axonserver.grpc.command.Command;
import io.axoniq.axonserver.grpc.command.CommandProviderInbound;
import io.axoniq.axonserver.grpc.command.CommandProviderOutbound;
import io.axoniq.axonserver.grpc.command.CommandResponse;
import io.axoniq.axonserver.grpc.command.CommandServiceGrpc;
import io.axoniq.axonserver.grpc.command.CommandSubscription;
import io.grpc.ClientInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.DispatchInterceptors;
import org.axonframework.axonserver.connector.ErrorCode;
import org.axonframework.axonserver.connector.util.ContextAddingInterceptor;
import org.axonframework.axonserver.connector.util.ExceptionSerializer;
import org.axonframework.axonserver.connector.util.FlowControllingStreamObserver;
import org.axonframework.axonserver.connector.util.TokenAddingInterceptor;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandResultMessage;
import org.axonframework.commandhandling.distributed.RoutingStrategy;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.Registration;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.modelling.command.ConcurrencyException;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.axonframework.axonserver.connector.util.ProcessingInstructionHelper.priority;
import static org.axonframework.commandhandling.GenericCommandResultMessage.asCommandResultMessage;


/**
 * Axon CommandBus implementation that connects to Axon Server to submit and receive commands.
 *
 * @author Marc Gathier
 * @since 3.4
 */
public class AxonServerCommandBus implements CommandBus {

    private static final Logger logger = LoggerFactory.getLogger(AxonServerCommandBus.class);

    private final AxonServerConnectionManager axonServerConnectionManager;
    private final AxonServerConfiguration configuration;
    private final CommandBus localSegment;
    private final CommandSerializer serializer;
    private final RoutingStrategy routingStrategy;
    private final CommandPriorityCalculator priorityCalculator;

    private final CommandRouterSubscriber commandRouterSubscriber;
    private final ClientInterceptor[] interceptors;
    private final DispatchInterceptors<CommandMessage<?>> dispatchInterceptors;

    /**
     * Instantiate an Axon Server Command Bus client. Will connect to an Axon Server instance to submit and receive
     * commands.
     *
     * @param axonServerConnectionManager a {@link AxonServerConnectionManager} which creates the connection to an Axon
     *                                    Server platform
     * @param configuration               the {@link AxonServerConfiguration} containing client and component names used
     *                                    to identify the application in Axon Server
     * @param localSegment                a {@link CommandBus} handling the incoming commands for the local application
     * @param serializer                  a {@link Serializer} used for de/serialization command requests and responses
     * @param routingStrategy             a {@link RoutingStrategy} defining where a given {@link CommandMessage} should
     *                                    be routed to
     */
    public AxonServerCommandBus(AxonServerConnectionManager axonServerConnectionManager,
                                AxonServerConfiguration configuration,
                                CommandBus localSegment,
                                Serializer serializer,
                                RoutingStrategy routingStrategy) {
        this(axonServerConnectionManager, configuration, localSegment, serializer, routingStrategy,
             CommandPriorityCalculator.defaultCommandPriorityCalculator());
    }

    /**
     * Instantiate an Axon Server Command Bus client. Will connect to an Axon Server instance to submit and receive
     * commands. Allows specifying a {@link CommandPriorityCalculator} to define the priority of command message among
     * one another.
     *
     * @param axonServerConnectionManager a {@link AxonServerConnectionManager} which creates the connection to an Axon
     *                                    Server platform
     * @param configuration               the {@link AxonServerConfiguration} containing client and component names used
     *                                    to identify the application in Axon Server
     * @param localSegment                a {@link CommandBus} handling the incoming commands for the local application
     * @param serializer                  a {@link Serializer} used for de/serialization command requests and responses
     * @param routingStrategy             a {@link RoutingStrategy} defining where a given {@link CommandMessage} should
     *                                    be routed to
     * @param priorityCalculator          a {@link CommandPriorityCalculator} calculating the request priority based on
     *                                    the content, and adds this priority to the request
     */
    public AxonServerCommandBus(AxonServerConnectionManager axonServerConnectionManager,
                                AxonServerConfiguration configuration,
                                CommandBus localSegment,
                                Serializer serializer,
                                RoutingStrategy routingStrategy,
                                CommandPriorityCalculator priorityCalculator) {
        this.axonServerConnectionManager = axonServerConnectionManager;
        this.configuration = configuration;
        this.localSegment = localSegment;
        this.serializer = new CommandSerializer(serializer, configuration);
        this.routingStrategy = routingStrategy;
        this.priorityCalculator = priorityCalculator;

        this.commandRouterSubscriber = new CommandRouterSubscriber();
        interceptors = new ClientInterceptor[]{
                new TokenAddingInterceptor(configuration.getToken()),
                new ContextAddingInterceptor(configuration.getContext())
        };
        dispatchInterceptors = new DispatchInterceptors<>();
    }

    @Override
    public <C> void dispatch(CommandMessage<C> command) {
        dispatch(command, (commandMessage, commandResultMessage) -> {
        });
    }

    @Override
    public <C, R> void dispatch(CommandMessage<C> commandMessage,
                                CommandCallback<? super C, ? super R> commandCallback) {
        logger.debug("Dispatch command [{}] with callback", commandMessage.getCommandName());
        doDispatch(dispatchInterceptors.intercept(commandMessage), commandCallback);
    }

    private <C, R> void doDispatch(CommandMessage<C> command, CommandCallback<? super C, ? super R> commandCallback) {
        AtomicBoolean serverResponded = new AtomicBoolean(false);
        try {
            Command grpcCommand = serializer.serialize(
                    command, routingStrategy.getRoutingKey(command), priorityCalculator.determinePriority(command)
            );

            CommandServiceGrpc.newStub(axonServerConnectionManager.getChannel())
                              .withInterceptors(interceptors)
                              .dispatch(grpcCommand,
                                        new StreamObserver<CommandResponse>() {
                                            @Override
                                            public void onNext(CommandResponse commandResponse) {
                                                serverResponded.set(true);
                                                logger.debug("Received command response [{}]", commandResponse);

                                                try {
                                                    CommandResultMessage<R> resultMessage =
                                                            serializer.deserialize(commandResponse);
                                                    commandCallback.onResult(command, resultMessage);
                                                } catch (Exception ex) {
                                                    commandCallback.onResult(command, asCommandResultMessage(ex));
                                                    logger.info("Failed to deserialize payload [{}] - Cause: {}",
                                                                commandResponse.getPayload().getData(),
                                                                ex.getCause().getMessage());
                                                }
                                            }

                                            @Override
                                            public void onError(Throwable throwable) {
                                                serverResponded.set(true);
                                                commandCallback.onResult(command, asCommandResultMessage(
                                                        ErrorCode.COMMAND_DISPATCH_ERROR.convert(
                                                                configuration.getClientId(), throwable
                                                        )
                                                ));
                                            }

                                            @Override
                                            public void onCompleted() {
                                                if (!serverResponded.get()) {
                                                    ErrorMessage errorMessage =
                                                            ErrorMessage.newBuilder()
                                                                        .setMessage("No result from command executor")
                                                                        .build();
                                                    commandCallback.onResult(command, asCommandResultMessage(
                                                            ErrorCode.COMMAND_DISPATCH_ERROR.convert(errorMessage))
                                                    );
                                                }
                                            }
                                        }
                              );
        } catch (Exception e) {
            logger.warn("There was a problem dispatching command [{}].", command, e);
            commandCallback.onResult(
                    command,
                    asCommandResultMessage(ErrorCode.COMMAND_DISPATCH_ERROR.convert(configuration.getClientId(), e))
            );
        }
    }

    @Override
    public Registration subscribe(String commandName, MessageHandler<? super CommandMessage<?>> messageHandler) {
        logger.debug("Subscribing command with name [{}]", commandName);
        commandRouterSubscriber.subscribe(commandName);
        return new AxonServerRegistration(
                localSegment.subscribe(commandName, messageHandler),
                () -> commandRouterSubscriber.unsubscribe(commandName)
        );
    }

    @Override
    public Registration registerHandlerInterceptor(
            MessageHandlerInterceptor<? super CommandMessage<?>> handlerInterceptor) {
        return localSegment.registerHandlerInterceptor(handlerInterceptor);
    }

    /**
     * Disconnect the command bus from the Axon Server.
     */
    public void disconnect() {
        commandRouterSubscriber.disconnect();
    }

    @Override
    public Registration registerDispatchInterceptor(
            MessageDispatchInterceptor<? super CommandMessage<?>> dispatchInterceptor) {
        return dispatchInterceptors.registerDispatchInterceptor(dispatchInterceptor);
    }

    private class CommandRouterSubscriber {

        private final CopyOnWriteArraySet<String> subscribedCommands = new CopyOnWriteArraySet<>();
        private final PriorityBlockingQueue<Command> commandQueue;
        private final ExecutorService executor = Executors.newFixedThreadPool(
                configuration.getCommandThreads(), new AxonThreadFactory("AxonServerCommandReceiver")
        );

        private volatile boolean subscribing;
        private volatile boolean running = true;
        private volatile StreamObserver<CommandProviderOutbound> subscriberStreamObserver;

        CommandRouterSubscriber() {
            axonServerConnectionManager.addReconnectListener(this::resubscribe);
            axonServerConnectionManager.addDisconnectListener(this::unsubscribeAll);
            commandQueue = new PriorityBlockingQueue<>(
                    1000, Comparator.comparingLong(c -> -priority(c.getProcessingInstructionsList()))
            );
            IntStream.range(0, configuration.getCommandThreads()).forEach(i -> executor.submit(this::commandExecutor));
        }

        private void commandExecutor() {
            logger.debug("Starting Command Executor");

            boolean interrupted = false;
            while (!interrupted && running) {
                try {
                    Command command = commandQueue.poll(1, TimeUnit.SECONDS);
                    if (command != null) {
                        try {
                            logger.debug("Received command: {}", command);
                            processCommand(command);
                        } catch (RuntimeException | OutOfDirectMemoryError e) {
                            logger.warn("CommandExecutor had an exception on command [{}]", command, e);
                        }
                    }
                } catch (InterruptedException e) {
                    logger.warn("Command Executor got interrupted", e);
                    Thread.currentThread().interrupt();
                    interrupted = true;
                }
            }
        }

        private void resubscribe() {
            if (subscribedCommands.isEmpty() || subscribing) {
                return;
            }

            try {
                StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver();
                subscribedCommands.forEach(command -> outboundStreamObserver.onNext(
                        CommandProviderOutbound.newBuilder().setSubscribe(
                                CommandSubscription.newBuilder()
                                                   .setCommand(command)
                                                   .setComponentName(configuration.getComponentName())
                                                   .setClientId(configuration.getClientId())
                                                   .setMessageId(UUID.randomUUID().toString())
                                                   .build()
                        ).build()
                ));
            } catch (Exception e) {
                logger.warn("Error while resubscribing - [{}]", e.getMessage());
            }
        }

        public void subscribe(String commandName) {
            subscribing = true;
            subscribedCommands.add(commandName);
            try {
                StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver();
                outboundStreamObserver.onNext(CommandProviderOutbound.newBuilder().setSubscribe(
                        CommandSubscription.newBuilder()
                                           .setCommand(commandName)
                                           .setClientId(configuration.getClientId())
                                           .setComponentName(configuration.getComponentName())
                                           .setMessageId(UUID.randomUUID().toString())
                                           .build()
                ).build());
            } catch (Exception e) {
                logger.debug("Subscribing command with name [{}] to Axon Server failed. "
                                     + "Will resubscribe when connection is established.",
                             commandName, e);
            } finally {
                subscribing = false;
            }
        }

        private void processCommand(Command command) {
            StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver();
            try {
                dispatchLocal(serializer.deserialize(command), outboundStreamObserver);
            } catch (RuntimeException throwable) {
                logger.error("Error while dispatching command [{}] - Cause: {}",
                             command.getName(), throwable.getMessage(), throwable);

                if (outboundStreamObserver == null) {
                    return;
                }

                CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                        CommandResponse.newBuilder()
                                       .setMessageIdentifier(UUID.randomUUID().toString())
                                       .setRequestIdentifier(command.getMessageIdentifier())
                                       .setErrorCode(ErrorCode.COMMAND_DISPATCH_ERROR.errorCode())
                                       .setErrorMessage(
                                               ExceptionSerializer.serialize(configuration.getClientId(), throwable)
                                       )
                ).build();

                outboundStreamObserver.onNext(response);
            }
        }

        private synchronized StreamObserver<CommandProviderOutbound> getSubscriberObserver() {
            if (subscriberStreamObserver != null) {
                return subscriberStreamObserver;
            }

            StreamObserver<CommandProviderInbound> commandsFromRoutingServer = new StreamObserver<CommandProviderInbound>() {
                @Override
                public void onNext(CommandProviderInbound commandToSubscriber) {
                    logger.debug("Received command from server: {}", commandToSubscriber);
                    if (commandToSubscriber.getRequestCase() == CommandProviderInbound.RequestCase.COMMAND) {
                        commandQueue.add(commandToSubscriber.getCommand());
                    }
                }

                @SuppressWarnings("Duplicates")
                @Override
                public void onError(Throwable ex) {
                    logger.warn("Received error from server: {}", ex.getMessage());
                    subscriberStreamObserver = null;
                    if (ex instanceof StatusRuntimeException
                            && ((StatusRuntimeException) ex).getStatus().getCode()
                                                            .equals(Status.UNAVAILABLE.getCode())) {
                        return;
                    }
                    resubscribe();
                }

                @Override
                public void onCompleted() {
                    logger.debug("Received completed from server");
                    subscriberStreamObserver = null;
                }
            };

            StreamObserver<CommandProviderOutbound> streamObserver =
                    axonServerConnectionManager.getCommandStream(commandsFromRoutingServer, interceptors);

            logger.info("Creating new subscriber");

            subscriberStreamObserver = new FlowControllingStreamObserver<>(
                    streamObserver,
                    configuration,
                    flowControl -> CommandProviderOutbound.newBuilder().setFlowControl(flowControl).build(),
                    t -> t.getRequestCase().equals(CommandProviderOutbound.RequestCase.COMMAND_RESPONSE)
            ).sendInitialPermits();
            return subscriberStreamObserver;
        }

        public void unsubscribe(String command) {
            subscribedCommands.remove(command);
            try {
                getSubscriberObserver().onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                        CommandSubscription.newBuilder()
                                           .setCommand(command)
                                           .setClientId(configuration.getClientId())
                                           .setMessageId(UUID.randomUUID().toString())
                                           .build()
                ).build());
            } catch (Exception ignored) {
                // This exception is ignored
            }
        }

        void unsubscribeAll() {
            for (String subscribedCommand : subscribedCommands) {
                try {
                    getSubscriberObserver().onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                            CommandSubscription.newBuilder()
                                               .setCommand(subscribedCommand)
                                               .setClientId(configuration.getClientId())
                                               .setMessageId(UUID.randomUUID().toString())
                                               .build()
                    ).build());
                } catch (Exception ignored) {
                    // This exception is ignored
                }
            }
            subscriberStreamObserver = null;
        }

        private <C> void dispatchLocal(CommandMessage<C> command,
                                       StreamObserver<CommandProviderOutbound> responseObserver) {
            logger.debug("Dispatch command [{}] locally", command.getCommandName());

            localSegment.dispatch(command, (commandMessage, commandResultMessage) -> {
                if (commandResultMessage.isExceptional()) {
                    Throwable throwable = commandResultMessage.exceptionResult();
                    CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                            CommandResponse.newBuilder()
                                           .setMessageIdentifier(UUID.randomUUID().toString())
                                           .setRequestIdentifier(command.getIdentifier())
                                           .setErrorCode(throwable instanceof ConcurrencyException
                                                                 ? ErrorCode.CONCURRENCY_EXCEPTION.errorCode()
                                                                 : ErrorCode.COMMAND_EXECUTION_ERROR.errorCode())
                                           .setErrorMessage(
                                                   ExceptionSerializer.serialize(configuration.getClientId(), throwable)
                                           )
                    ).build();

                    responseObserver.onNext(response);
                    logger.info("Failed to dispatch command [{}] locally - Cause: {}",
                                command.getCommandName(), throwable.getMessage(), throwable);
                } else {
                    logger.debug("Succeeded in dispatching command [{}] locally", command.getCommandName());
                    responseObserver.onNext(serializer.serialize(commandResultMessage, command.getIdentifier()));
                }
            });
        }

        void disconnect() {
            if (subscriberStreamObserver != null) {
                subscriberStreamObserver.onCompleted();
            }
            running = false;
            executor.shutdown();
        }
    }
}
