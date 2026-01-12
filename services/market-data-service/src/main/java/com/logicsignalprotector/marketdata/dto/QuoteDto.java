package com.logicsignalprotector.marketdata.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record QuoteDto(
    String secId,
    String board,
    BigDecimal lastPrice,
    BigDecimal change,
    BigDecimal changePercent,
    BigDecimal volume,
    OffsetDateTime time) {}
