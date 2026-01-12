package com.logicsignalprotector.marketdata.dto;

import java.util.List;

public record TradesResponse(
    String correlationId, String from, Integer limit, List<TradeDto> trades) {}
