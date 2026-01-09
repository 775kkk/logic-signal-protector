package com.logicsignalprotector.marketdata.usecase;

import com.logicsignalprotector.marketdata.dto.CandleDto;
import com.logicsignalprotector.marketdata.dto.InstrumentDto;
import com.logicsignalprotector.marketdata.dto.OrderBookDto;
import com.logicsignalprotector.marketdata.dto.QuoteDto;
import com.logicsignalprotector.marketdata.dto.TradeDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MarketDataUseCase {
  List<InstrumentDto> getInstruments(
      String engine, String market, String board, Optional<String> filter, int limit, int offset);

  QuoteDto getQuote(String engine, String market, String board, String sec);

  List<CandleDto> getCandles(
      String engine,
      String market,
      String board,
      String sec,
      int interval,
      OffsetDateTime from,
      OffsetDateTime till);

  OrderBookDto getOrderBook(String engine, String market, String board, String sec, int depth);

  List<TradeDto> getTrades(
      String engine,
      String market,
      String board,
      String sec,
      Optional<String> from,
      Optional<Integer> limit);
}
