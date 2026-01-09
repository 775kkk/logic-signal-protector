package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.InstrumentDto;
import com.logicsignalprotector.marketdata.dto.InstrumentsResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/v1/instruments")
@Validated
public class InstrumentsController {
  private final MarketDataUseCase marketDataUseCase;

  public InstrumentsController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public InstrumentsResponse getInstruments(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam(required = false) String filter,
      @RequestParam(defaultValue = "100") @Min(1) @Max(5000) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset,
      @RequestParam(required = false) String correlationId) {
    List<InstrumentDto> instruments =
        marketDataUseCase.getInstruments(
            engine, market, board, Optional.ofNullable(filter), limit, offset);
    return new InstrumentsResponse(correlationId, instruments, offset, limit, instruments.size());
  }
}
