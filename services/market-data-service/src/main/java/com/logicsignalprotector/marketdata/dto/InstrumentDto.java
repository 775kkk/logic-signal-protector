package com.logicsignalprotector.marketdata.dto;

import java.math.BigDecimal;

public record InstrumentDto(
    String secId,
    String shortName,
    String name,
    Integer lotSize,
    BigDecimal prevPrice,
    BigDecimal lastPrice,
    String currency,
    String board) {}
