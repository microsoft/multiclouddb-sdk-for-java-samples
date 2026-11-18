// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderServiceSampleTest {
    @Test
    void conflictingPreexistingOrderDoesNotRunLifecycleMutations() {
        Order existing = order("order-1");
        boolean ran = OrderServiceSample.runIfOwned(List.of(), existing, () -> {
            throw new AssertionError("lifecycle mutation ran for a preexisting order");
        });

        assertFalse(ran);
        assertTrue(OrderServiceSample.runIfOwned(List.of(existing), existing, () -> { }));
    }

    @Test
    void cleanupAttemptsEveryOwnedOrderAndReportsCleanupOnlyFailures() {
        List<Order> orders = orders();
        List<String> attempted = new ArrayList<>();

        OrderServiceSample.CleanupException failure = assertThrows(
                OrderServiceSample.CleanupException.class,
                () -> OrderServiceSample.runThenCleanUp(orders, () -> { }, order -> {
                    attempted.add(order.orderId());
                    if (!order.orderId().equals("order-2")) {
                        throw new IllegalStateException("delete failed");
                    }
                }));

        assertEquals(List.of("order-1", "order-2", "order-3"), attempted);
        assertEquals(List.of("order-1", "order-3"), failure.orderIds());
        assertEquals("Cleanup failed for orders: [order-1, order-3]", failure.getMessage());
    }

    @Test
    void walkthroughFailureRemainsPrimaryWhenCleanupAlsoFails() {
        List<Order> orders = orders();
        List<String> attempted = new ArrayList<>();
        RuntimeException walkthroughFailure = new IllegalArgumentException("walkthrough failed");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OrderServiceSample.runThenCleanUp(orders, () -> {
                    throw walkthroughFailure;
                }, order -> {
                    attempted.add(order.orderId());
                    if (!order.orderId().equals("order-2")) {
                        throw new IllegalStateException("delete failed");
                    }
                }));

        assertSame(walkthroughFailure, failure);
        assertEquals(List.of("order-1", "order-2", "order-3"), attempted);
        OrderServiceSample.CleanupException cleanupFailure =
                (OrderServiceSample.CleanupException) failure.getSuppressed()[0];
        assertEquals(List.of("order-1", "order-3"), cleanupFailure.orderIds());
    }

    private static List<Order> orders() {
        return new ArrayList<>(List.of(order("order-1"), order("order-2"), order("order-3")));
    }

    private static Order order(String orderId) {
        return new Order("customer-1", orderId, "key-" + orderId, List.of(), 0L,
                "2026-01-02T03:04:05Z", null, OrderStatus.PLACED);
    }
}
