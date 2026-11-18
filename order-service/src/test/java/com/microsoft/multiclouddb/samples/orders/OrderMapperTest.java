// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderMapperTest {
    private final OrderMapper mapper = new OrderMapper();

    @Test
    void mapsNestedOrderToSupportedValuesAndBack() {
        Order order = new Order("customer-1", "order-1", "key-1",
                List.of(new OrderLine("coffee", 2, 1299L)), 2598L,
                "2026-01-02T03:04:05Z", null, OrderStatus.PLACED);

        Map<String, Object> document = mapper.toDocument(order);

        assertInstanceOf(Long.class, document.get("totalCents"));
        assertInstanceOf(Long.class, ((Map<?, ?>) ((List<?>) document.get("lines")).get(0))
                .get("unitPriceCents"));
        assertFalse(document.containsKey("note"));
        assertEquals(order, mapper.toOrder(document));
        assertSupported(document);
    }

    @Test
    void ignoresProviderInjectedKeysWhenReading() {
        Order order = new Order("customer-1", "order-1", "key-1", List.of(), 0L,
                "2026-01-02T03:04:05Z", null, OrderStatus.SHIPPED);
        Map<String, Object> document = new java.util.LinkedHashMap<>(mapper.toDocument(order));
        document.put("id", "order-1");
        document.put("partitionKey", "customer-1");
        document.put("sortKey", "order-1");

        Order mapped = mapper.toOrder(document);

        assertEquals(order, mapped);
        assertNull(mapped.note());
    }

    private static void assertSupported(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                assertInstanceOf(String.class, key);
                assertSupported(nested);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(OrderMapperTest::assertSupported);
            return;
        }
        assertTrue(false, "Unsupported mapped type: " + value.getClass());
    }
}
