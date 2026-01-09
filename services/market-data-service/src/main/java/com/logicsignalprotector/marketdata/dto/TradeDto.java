package com.logicsignalprotector.marketdata.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradeDto(
    Long tradeNo, OffsetDateTime time, BigDecimal price, BigDecimal quantity, String side) {}
