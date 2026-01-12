package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.MarketStatusDto;
import com.logicsignalprotector.marketdata.dto.MarketStatusResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/v1/status")
@Validated
public class MarketStatusController {
  private final MarketDataUseCase marketDataUseCase;

  public MarketStatusController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public MarketStatusResponse getStatus(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam(defaultValue = "SBER") String sec,
      @RequestParam(required = false) String correlationId) {
    MarketStatusDto status = marketDataUseCase.getMarketStatus(engine, market, board, sec);
    return new MarketStatusResponse(correlationId, status);
  }
}
