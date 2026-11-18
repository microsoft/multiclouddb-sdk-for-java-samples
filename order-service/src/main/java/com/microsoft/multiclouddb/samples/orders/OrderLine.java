// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.samples.orders;

public record OrderLine(String productId, int quantity, long unitPriceCents) {
}
