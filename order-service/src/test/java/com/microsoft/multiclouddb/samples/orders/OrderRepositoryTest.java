// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderRepositoryTest {
    @Test
    void ambiguousCreateConflictIsOwnedOnlyWhenReadbackMatchesIdempotencyKey() {
        Order attempted = order("request-1");

        assertTrue(repositoryWithRetryConflict(order("request-1")).place(attempted));
        assertFalse(repositoryWithRetryConflict(order("preexisting-request")).place(attempted));
    }

    private static Order order(String idempotencyKey) {
        return new Order("customer-1", "order-1", idempotencyKey, List.of(), 0L,
                "2026-01-02T03:04:05Z", null, OrderStatus.PLACED);
    }

    private static OrderRepository repositoryWithRetryConflict(Order storedOrder) {
        AtomicInteger createAttempts = new AtomicInteger();
        Map<String, Object> storedDocument = new OrderMapper().toDocument(storedOrder);
        MulticloudDbClient client = (MulticloudDbClient) Proxy.newProxyInstance(
                MulticloudDbClient.class.getClassLoader(),
                new Class<?>[] { MulticloudDbClient.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("create")) {
                        if (createAttempts.incrementAndGet() == 1) {
                            throw failure(MulticloudDbErrorCategory.THROTTLED, true, 429);
                        }
                        throw failure(MulticloudDbErrorCategory.CONFLICT, false, 409);
                    }
                    if (method.getName().equals("read")) {
                        return new DocumentResult(storedDocument);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new OrderRepository(client, new ResourceAddress("db", "orders"), new OrderMapper());
    }

    private static MulticloudDbException failure(
            MulticloudDbErrorCategory category, boolean retryable, int statusCode) {
        return new MulticloudDbException(new MulticloudDbError(category, "simulated", ProviderId.fromId("cosmos"),
                "create", retryable, statusCode, Map.of()));
    }
}
