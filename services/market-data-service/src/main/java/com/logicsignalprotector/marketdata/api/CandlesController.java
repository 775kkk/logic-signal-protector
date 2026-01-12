package com.logicsignalprotector.marketdata.api;

import com.logicsignalprotector.marketdata.dto.CandleDto;
import com.logicsignalprotector.marketdata.dto.CandlesResponse;
import com.logicsignalprotector.marketdata.usecase.MarketDataUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/market/v1/candles")
@Validated
public class CandlesController {
  private static final DateTimeFormatter ISS_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final MarketDataUseCase marketDataUseCase;

  public CandlesController(MarketDataUseCase marketDataUseCase) {
    this.marketDataUseCase = marketDataUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")
  public CandlesResponse getCandles(
      @RequestParam(defaultValue = "stock") String engine,
      @RequestParam(defaultValue = "shares") String market,
      @RequestParam(defaultValue = "TQBR") String board,
      @RequestParam @NotBlank String sec,
      @RequestParam @Min(1) @Max(1440) int interval,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String till,
      @RequestParam(required = false) String correlationId) {
    if (!(interval == 1 || interval == 10 || interval == 60 || interval == 1440)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "interval must be one of: 1, 10, 60, 1440");
    }
    OffsetDateTime fromDate = parseDate(from, false);
    OffsetDateTime tillDate = parseDate(till, true);
    List<CandleDto> candles =
        marketDataUseCase.getCandles(engine, market, board, sec, interval, fromDate, tillDate);
    return new CandlesResponse(correlationId, sec, board, interval, fromDate, tillDate, candles);
  }

  private static OffsetDateTime parseDate(String value, boolean endOfDay) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      if (value.contains("T")) {
        try {
          return OffsetDateTime.parse(value);
        } catch (Exception ex) {
          LocalDateTime dateTime = LocalDateTime.parse(value);
          return dateTime.atOffset(ZoneOffset.UTC);
        }
      }
      if (value.contains(":")) {
        LocalDateTime dateTime = LocalDateTime.parse(value, ISS_DATE_TIME);
        return dateTime.atOffset(ZoneOffset.UTC);
      }
      LocalDate date = LocalDate.parse(value);
      if (endOfDay) {
        return date.atTime(23, 59, 59).atOffset(ZoneOffset.UTC);
      }
      return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    } catch (Exception ex) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid date format: " + value, ex);
    }
  }
}
