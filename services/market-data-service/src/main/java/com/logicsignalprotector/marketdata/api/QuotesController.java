package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.QuoteDto;
import com.logicsignalprotector.marketdata.dto.QuoteResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/v1/quotes")
@Validated
public class QuotesController {
  private final MarketDataUseCase marketDataUseCase;

  public QuotesController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public QuoteResponse getQuotes(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam @NotBlank String sec,
      @RequestParam(required = false) String correlationId) {
    QuoteDto quote = marketDataUseCase.getQuote(engine, market, board, sec);
    return new QuoteResponse(correlationId, quote);
  }
}
