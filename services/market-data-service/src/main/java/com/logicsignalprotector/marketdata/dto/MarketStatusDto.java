package com.logicsignalprotector.marketdata.dto;

import java.time.OffsetDateTime;

public record MarketStatusDto(
    String exchange, String board, String secId, String tradingStatus, OffsetDateTime time) {}
