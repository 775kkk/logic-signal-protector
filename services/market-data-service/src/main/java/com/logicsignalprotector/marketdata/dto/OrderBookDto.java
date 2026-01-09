package com.logicsignalprotector.marketdata.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderBookDto(
    String secId,
    String board,
    OffsetDateTime time,
    List<OrderBookEntryDto> bids,
    List<OrderBookEntryDto> asks) {}
