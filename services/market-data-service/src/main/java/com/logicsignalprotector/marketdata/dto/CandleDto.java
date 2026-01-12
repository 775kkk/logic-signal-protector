package com.logicsignalprotector.marketdata.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CandleDto(
    OffsetDateTime begin,
    OffsetDateTime end,
    BigDecimal open,
    BigDecimal close,
    BigDecimal high,
    BigDecimal low,
    BigDecimal volume) {}
