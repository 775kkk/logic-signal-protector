package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.TradeDto;
import com.logicsignalprotector.marketdata.dto.TradesResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/market/v1/trades")
@Validated
public class TradesController {
  private static final Set<Integer> ALLOWED_LIMITS = Set.of(1, 10, 100, 1000, 5000);

  private final MarketDataUseCase marketDataUseCase;

  public TradesController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public TradesResponse getTrades(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam @NotBlank String sec,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String correlationId) {
    if (limit != null && !ALLOWED_LIMITS.contains(limit)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "limit must be one of: 1, 10, 100, 1000, 5000");
    }
    List<TradeDto> trades =
        marketDataUseCase.getTrades(
            engine, market, board, sec, Optional.ofNullable(from), Optional.ofNullable(limit));
    return new TradesResponse(correlationId, from, limit, trades);
  }
}
