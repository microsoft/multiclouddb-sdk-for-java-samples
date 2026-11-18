// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.SortDirection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrderRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderRepository.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int PAGE_SIZE = 2;

    private final MulticloudDbClient client;
    private final ResourceAddress address;
    private final OrderMapper mapper;

    public OrderRepository(MulticloudDbClient client, ResourceAddress address, OrderMapper mapper) {
        this.client = client;
        this.address = address;
        this.mapper = mapper;
    }

    public boolean place(Order order) {
        boolean hadRetryableFailure = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                client.create(address, key(order), mapper.toDocument(order));
                return true;
            } catch (MulticloudDbException exception) {
                logError(exception);
                if (is(exception, MulticloudDbErrorCategory.CONFLICT)) {
                    if (!hadRetryableFailure) {
                        return false;
                    }
                    Order existing = get(order.customerId(), order.orderId());
                    return existing != null && isSameCreateAttempt(order, existing);
                }
                if (is(exception, MulticloudDbErrorCategory.AUTHENTICATION_FAILED)
                        || is(exception, MulticloudDbErrorCategory.AUTHORIZATION_FAILED)
                        || !exception.error().retryable() || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                hadRetryableFailure = true;
                pause(attempt, exception);
            }
        }
        return false;
    }

    public Order get(String customerId, String orderId) {
        try {
            return retry(() -> {
                var result = client.read(address, MulticloudDbKey.of(customerId, orderId));
                return result == null ? null : mapper.toOrder(result.document());
            });
        } catch (MulticloudDbException exception) {
            if (is(exception, MulticloudDbErrorCategory.NOT_FOUND)) {
                return null;
            }
            throw exception;
        }
    }

    public boolean updateStatus(String customerId, String orderId, OrderStatus status) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Order current = get(customerId, orderId);
            if (current == null) {
                return false;
            }
            Order updated = new Order(current.customerId(), current.orderId(), current.idempotencyKey(),
                    current.lines(), current.totalCents(), current.placedAt(), current.note(), status);
            try {
                retry(() -> {
                    client.update(address, MulticloudDbKey.of(customerId, orderId), mapper.toDocument(updated));
                    return null;
                });
                return true;
            } catch (MulticloudDbException exception) {
                if (is(exception, MulticloudDbErrorCategory.NOT_FOUND)) {
                    return false;
                }
                if (!is(exception, MulticloudDbErrorCategory.CONFLICT) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                logError(exception);
                pause(attempt, exception);
            }
        }
        return false;
    }

    public void cancel(String customerId, String orderId) {
        try {
            retry(() -> {
                client.delete(address, MulticloudDbKey.of(customerId, orderId));
                return null;
            });
        } catch (MulticloudDbException exception) {
            if (!is(exception, MulticloudDbErrorCategory.NOT_FOUND)) {
                throw exception;
            }
        }
    }

    public List<Order> listByCustomer(String customerId) {
        QueryRequest.Builder query = QueryRequest.builder()
                .partitionKey(customerId)
                .maxPageSize(PAGE_SIZE);
        if (client.capabilities().isSupported(Capability.ORDER_BY)) {
            query.orderBy("placedAt", SortDirection.ASC);
            return queryOrders(query.build());
        }
        return queryOrders(query.build()).stream()
                .sorted(Comparator.comparing(Order::placedAt))
                .toList();
    }

    public List<Order> listByStatus(OrderStatus status) {
        if (!client.capabilities().isSupported(Capability.CROSS_PARTITION_QUERY)) {
            throw new UnsupportedOperationException("Provider does not support cross-partition queries");
        }
        QueryRequest request = QueryRequest.builder()
                .expression("status = @status")
                .parameter("status", status.name())
                .maxPageSize(PAGE_SIZE)
                .build();
        return queryOrders(request);
    }

    private List<Order> queryOrders(QueryRequest baseRequest) {
        List<Order> orders = new ArrayList<>();
        String token = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .expression(baseRequest.expression())
                    .parameters(baseRequest.parameters())
                    .partitionKey(baseRequest.partitionKey())
                    .maxPageSize(PAGE_SIZE)
                    .continuationToken(token);
            if (!baseRequest.orderBy().isEmpty()) {
                var order = baseRequest.orderBy().get(0);
                request.orderBy(order.field(), order.direction());
            }
            QueryPage page = retry(() -> client.query(address, request.build()));
            page.items().stream().map(mapper::toOrder).forEach(orders::add);
            token = page.continuationToken();
        } while (token != null);
        return orders;
    }

    private static MulticloudDbKey key(Order order) {
        return MulticloudDbKey.of(order.customerId(), order.orderId());
    }

    static boolean isSameCreateAttempt(Order attempted, Order existing) {
        return attempted.idempotencyKey() != null
                && attempted.idempotencyKey().equals(existing.idempotencyKey());
    }

    private static boolean is(MulticloudDbException exception, MulticloudDbErrorCategory category) {
        return category.equals(exception.error().category());
    }

    private static <T> T retry(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (MulticloudDbException exception) {
                logError(exception);
                if (is(exception, MulticloudDbErrorCategory.AUTHENTICATION_FAILED)
                        || is(exception, MulticloudDbErrorCategory.AUTHORIZATION_FAILED)
                        || !exception.error().retryable() || attempt >= MAX_ATTEMPTS) {
                    throw exception;
                }
                pause(attempt, exception);
            }
        }
    }

    private static void pause(int attempt, MulticloudDbException exception) {
        try {
            Thread.sleep(100L << (attempt - 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static void logError(MulticloudDbException exception) {
        var error = exception.error();
        LOGGER.warn("Database operation failed: category={}, statusCode={}, retryable={}",
                error.category(), error.statusCode(), error.retryable());
    }
}
