package com.logicsignalprotector.marketdata.dto;

import java.util.List;

public record InstrumentsResponse(
    String correlationId, List<InstrumentDto> instruments, int offset, int limit, int total) {}
