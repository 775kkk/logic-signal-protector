package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.OrderBookDto;
import com.logicsignalprotector.marketdata.dto.OrderBookResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/v1/orderbook")
@Validated
public class OrderBookController {
  private final MarketDataUseCase marketDataUseCase;

  public OrderBookController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public OrderBookResponse getOrderBook(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam @NotBlank String sec,
      @RequestParam(defaultValue = "10") @Min(1) @Max(50) int depth,
      @RequestParam(required = false) String correlationId) {
    OrderBookDto orderBook = marketDataUseCase.getOrderBook(engine, market, board, sec, depth);
    return new OrderBookResponse(correlationId, orderBook);
  }
}
