// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrderServiceSample {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderServiceSample.class);

    private OrderServiceSample() {
    }

    public static void main(String[] args) throws Exception {
        Properties properties = loadProperties();
        MulticloudDbClientConfig config = buildConfig(properties);
        String database = properties.getProperty("multiclouddb.database", "multiclouddb-orders");
        String collection = properties.getProperty("multiclouddb.collection", "orders");
        ResourceAddress address = new ResourceAddress(database, collection);
        OrderMapper mapper = new OrderMapper();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<Order> orders = sampleOrders(runId);
        List<Order> createdOrders = new ArrayList<>();

        try (var client = MulticloudDbClientFactory.create(config)) {
            client.provisionSchema(Map.of(database, List.of(collection)));
            OrderRepository repository = new OrderRepository(client, address, mapper);
            runThenCleanUp(createdOrders, () -> walkthrough(orders, runId, createdOrders, repository, client),
                    order -> repository.cancel(order.customerId(), order.orderId()));
        }
    }

    static void runThenCleanUp(List<Order> ownedOrders, Runnable walkthrough, Consumer<Order> delete) {
        try {
            walkthrough.run();
        } catch (RuntimeException | Error primaryFailure) {
            List<String> remaining = cleanUp(ownedOrders, delete);
            if (!remaining.isEmpty()) {
                primaryFailure.addSuppressed(new CleanupException(remaining));
            }
            throw primaryFailure;
        }

        List<String> remaining = cleanUp(ownedOrders, delete);
        if (!remaining.isEmpty()) {
            throw new CleanupException(remaining);
        }
    }

    private static void walkthrough(
            List<Order> orders,
            String runId,
            List<Order> createdOrders,
            OrderRepository repository,
            MulticloudDbClient client) {
        for (Order order : orders) {
            boolean created = repository.place(order);
            if (created) {
                createdOrders.add(order);
            }
            System.out.printf("Placed %s: %s%n", order.orderId(),
                    created ? "created" : "already exists");
        }
        System.out.println("Read: " + repository.get(orders.get(0).customerId(), orders.get(0).orderId()));

        for (String customer : List.of("customer-" + runId + "-1",
                "customer-" + runId + "-2", "customer-" + runId + "-3")) {
            System.out.printf("Orders for %s: %d%n", customer, repository.listByCustomer(customer).size());
        }

        Order first = orders.get(0);
        boolean owned = runIfOwned(createdOrders, first, () -> {
            repository.updateStatus(first.customerId(), first.orderId(), OrderStatus.PAID);
            if (client.capabilities().isSupported(Capability.CROSS_PARTITION_QUERY)) {
                System.out.printf("Paid orders across customers: %d%n",
                        repository.listByStatus(OrderStatus.PAID).size());
            } else {
                System.out.println("Skipping operations status query: cross-partition queries are unsupported.");
            }
            repository.cancel(first.customerId(), first.orderId());
            createdOrders.remove(first);
        });
        if (!owned) {
            System.out.printf("Skipping lifecycle changes for %s: it was not created by this invocation.%n",
                    first.orderId());
        }

    }

    static boolean runIfOwned(List<Order> ownedOrders, Order order, Runnable operation) {
        if (!ownedOrders.contains(order)) {
            return false;
        }
        operation.run();
        return true;
    }

    private static List<String> cleanUp(List<Order> ownedOrders, Consumer<Order> delete) {
        List<String> remaining = new ArrayList<>();
        for (Order order : List.copyOf(ownedOrders)) {
            try {
                delete.accept(order);
                ownedOrders.remove(order);
            } catch (RuntimeException failure) {
                remaining.add(order.orderId());
                if (failure instanceof MulticloudDbException exception) {
                    var error = exception.error();
                    LOGGER.warn("Cleanup failed for order {}: category={}, statusCode={}, retryable={}",
                            order.orderId(), error.category(), error.statusCode(), error.retryable());
                } else {
                    LOGGER.warn("Cleanup failed for order {}: {}", order.orderId(),
                            failure.getClass().getSimpleName());
                }
            }
        }
        return remaining;
    }

    static final class CleanupException extends RuntimeException {
        private final List<String> orderIds;

        CleanupException(List<String> orderIds) {
            super("Cleanup failed for orders: " + orderIds);
            this.orderIds = List.copyOf(orderIds);
        }

        List<String> orderIds() {
            return orderIds;
        }
    }

    private static List<Order> sampleOrders(String runId) {
        List<Order> orders = new ArrayList<>();
        for (int customer = 1; customer <= 3; customer++) {
            for (int number = 1; number <= 3; number++) {
                String customerId = "customer-" + runId + "-" + customer;
                String orderId = "order-" + runId + "-" + customer + "-" + number;
                List<OrderLine> lines = List.of(
                        new OrderLine("coffee-" + number, 2, 1299L),
                        new OrderLine("mug-" + number, 1, 850L));
                orders.add(new Order(customerId, orderId, UUID.randomUUID().toString(), lines, 3448L,
                        Instant.now().toString(), null, OrderStatus.PLACED));
            }
        }
        return orders;
    }

    private static Properties loadProperties() throws IOException {
        String resource = System.getProperty("multiclouddb.config", "orders-cosmos.properties");
        Properties properties = new Properties();
        try (InputStream input = OrderServiceSample.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Config resource not found: " + resource);
            }
            properties.load(input);
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("multiclouddb.")) {
                properties.setProperty(key, System.getProperty(key));
            }
        }
        return properties;
    }

    private static MulticloudDbClientConfig buildConfig(Properties properties) {
        var builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.fromId(properties.getProperty("multiclouddb.provider", "cosmos")));
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("multiclouddb.connection.")) {
                builder.connection(key.substring("multiclouddb.connection.".length()), properties.getProperty(key));
            } else if (key.startsWith("multiclouddb.auth.")) {
                builder.auth(key.substring("multiclouddb.auth.".length()), properties.getProperty(key));
            }
        }
        return builder.build();
    }
}
