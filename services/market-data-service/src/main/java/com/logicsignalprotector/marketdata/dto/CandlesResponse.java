package com.logicsignalprotector.marketdata.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CandlesResponse(
    String correlationId,
    String secId,
    String board,
    int interval,
    OffsetDateTime from,
    OffsetDateTime till,
    List<CandleDto> candles) {}
