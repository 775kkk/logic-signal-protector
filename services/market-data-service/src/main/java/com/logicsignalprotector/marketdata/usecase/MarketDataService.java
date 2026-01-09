package com.logicsignalprotector.marketdata.usecase;

import com.logicsignalprotector.marketdata.client.MoexClient;
import com.logicsignalprotector.marketdata.dto.CandleDto;
import com.logicsignalprotector.marketdata.dto.InstrumentDto;
import com.logicsignalprotector.marketdata.dto.OrderBookDto;
import com.logicsignalprotector.marketdata.dto.OrderBookEntryDto;
import com.logicsignalprotector.marketdata.dto.QuoteDto;
import com.logicsignalprotector.marketdata.dto.TradeDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService implements MarketDataUseCase {
  private final MoexClient moexClient;

  public MarketDataService(MoexClient moexClient) {
    this.moexClient = moexClient;
  }

  @Override
  public List<InstrumentDto> getInstruments(
      String engine, String market, String board, Optional<String> filter, int limit, int offset) {
    return moexClient.getInstruments(engine, market, board, filter, limit, offset);
  }

  @Override
  public QuoteDto getQuote(String engine, String market, String board, String sec) {
    return moexClient.getQuote(engine, market, board, sec);
  }

  @Override
  public List<CandleDto> getCandles(
      String engine,
      String market,
      String board,
      String sec,
      int interval,
      OffsetDateTime from,
      OffsetDateTime till) {
    return moexClient.getCandles(engine, market, board, sec, interval, from, till);
  }

  @Override
  public OrderBookDto getOrderBook(
      String engine, String market, String board, String sec, int depth) {
    OrderBookDto orderBook = moexClient.getOrderBook(engine, market, board, sec);
    List<OrderBookEntryDto> bids =
        orderBook.bids().stream().limit(depth).collect(Collectors.toList());
    List<OrderBookEntryDto> asks =
        orderBook.asks().stream().limit(depth).collect(Collectors.toList());
    return new OrderBookDto(orderBook.secId(), orderBook.board(), orderBook.time(), bids, asks);
  }

  @Override
  public List<TradeDto> getTrades(
      String engine,
      String market,
      String board,
      String sec,
      Optional<String> from,
      Optional<Integer> limit) {
    return moexClient.getTrades(engine, market, board, sec, from, limit);
  }
}
