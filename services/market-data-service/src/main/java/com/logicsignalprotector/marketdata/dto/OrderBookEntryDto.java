package com.logicsignalprotector.marketdata.dto;

import java.math.BigDecimal;

public record OrderBookEntryDto(String side, BigDecimal price, BigDecimal quantity) {}
