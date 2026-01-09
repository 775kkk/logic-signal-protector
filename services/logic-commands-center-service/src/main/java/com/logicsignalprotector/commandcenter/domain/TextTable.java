package com.logicsignalprotector.commandcenter.domain;

import java.util.ArrayList;
import java.util.List;

public class TextTable {

  public String render(List<String> headers, List<List<String>> rows) {
    List<List<String>> allRows = new ArrayList<>();
    if (headers != null && !headers.isEmpty()) {
      allRows.add(headers);
    }
    if (rows != null) {
      allRows.addAll(rows);
    }

    int cols = headers == null ? 0 : headers.size();
    for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
      cols = Math.max(cols, row == null ? 0 : row.size());
    }
    int[] widths = new int[cols];

    for (List<String> row : allRows) {
      if (row == null) continue;
      for (int i = 0; i < cols; i++) {
        String val = i < row.size() && row.get(i) != null ? row.get(i) : "";
        widths[i] = Math.max(widths[i], val.length());
      }
    }

    StringBuilder sb = new StringBuilder();
    if (headers != null && !headers.isEmpty()) {
      sb.append(row(headers, widths)).append("\n");
      sb.append(separator(widths)).append("\n");
    }
    if (rows != null) {
      for (List<String> row : rows) {
        sb.append(row(row, widths)).append("\n");
      }
    }
    return sb.toString().trim();
  }

  private static String row(List<String> row, int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < widths.length; i++) {
      String val = row != null && i < row.size() && row.get(i) != null ? row.get(i) : "";
      if (i > 0) sb.append(" | ");
      sb.append(padRight(val, widths[i]));
    }
    return sb.toString();
  }

  private static String separator(int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < widths.length; i++) {
      if (i > 0) sb.append("-+-");
      sb.append(repeat("-", widths[i]));
    }
    return sb.toString();
  }

  private static String padRight(String s, int width) {
    if (s.length() >= width) return s;
    return s + repeat(" ", width - s.length());
  }

  private static String repeat(String s, int count) {
    if (count <= 0) return "";
    return s.repeat(count);
  }
}
