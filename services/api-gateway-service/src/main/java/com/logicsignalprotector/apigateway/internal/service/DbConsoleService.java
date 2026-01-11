package com.logicsignalprotector.apigateway.internal.service;

import com.logicsignalprotector.apigateway.internal.api.dto.InternalDbDtos;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DbConsoleService {
  private static final int DEFAULT_MAX_ROWS = 50;
  private static final int MAX_ROWS_CAP = 1000;
  private static final HexFormat HEX = HexFormat.of();

  private final JdbcTemplate jdbc;

  public DbConsoleService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public InternalDbDtos.DbQueryResponse execute(String sql, Integer maxRows) {
    if (sql == null || sql.isBlank()) {
      return new InternalDbDtos.DbQueryResponse(
          false, "ERROR", List.of(), List.of(), null, false, "empty sql");
    }
    String trimmed = sql.trim();
    int limit = normalizeLimit(maxRows);
    try {
      if (isQuery(trimmed)) {
        return query(trimmed, limit);
      }
      int updated = jdbc.update(trimmed);
      return new InternalDbDtos.DbQueryResponse(
          true, "UPDATE", List.of(), List.of(), (long) updated, false, null);
    } catch (Exception e) {
      return new InternalDbDtos.DbQueryResponse(
          false, "ERROR", List.of(), List.of(), null, false, e.getMessage());
    }
  }

  private InternalDbDtos.DbQueryResponse query(String sql, int limit) {
    return jdbc.query(
        sql,
        rs -> {
          ResultSetMetaData meta = rs.getMetaData();
          int columnsCount = meta.getColumnCount();
          List<String> columns = new ArrayList<>(columnsCount);
          for (int i = 1; i <= columnsCount; i++) {
            columns.add(meta.getColumnLabel(i));
          }
          List<List<String>> rows = new ArrayList<>();
          int count = 0;
          boolean truncated = false;
          while (rs.next()) {
            if (count >= limit) {
              truncated = true;
              break;
            }
            List<String> row = new ArrayList<>(columnsCount);
            for (int i = 1; i <= columnsCount; i++) {
              Object value = rs.getObject(i);
              row.add(formatValue(value));
            }
            rows.add(row);
            count++;
          }
          return new InternalDbDtos.DbQueryResponse(
              true, "QUERY", columns, rows, null, truncated, null);
        });
  }

  private static int normalizeLimit(Integer maxRows) {
    if (maxRows == null || maxRows < 1) {
      return DEFAULT_MAX_ROWS;
    }
    return Math.min(maxRows, MAX_ROWS_CAP);
  }

  private static boolean isQuery(String sql) {
    String t = sql.trim().toLowerCase(Locale.ROOT);
    return t.startsWith("select")
        || t.startsWith("with")
        || t.startsWith("show")
        || t.startsWith("explain");
  }

  private static String formatValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof byte[] bytes) {
      return "0x" + HEX.formatHex(bytes);
    }
    return String.valueOf(value);
  }
}
