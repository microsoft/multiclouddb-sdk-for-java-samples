// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

import java.util.List;

public record Order(
        String customerId,
        String orderId,
        String idempotencyKey,
        List<OrderLine> lines,
        long totalCents,
        String placedAt,
        String note,
        OrderStatus status) {
}
