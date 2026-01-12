package com.logicsignalprotector.apitelegram.render;

import com.logicsignalprotector.apitelegram.client.TelegramBotClient;
import com.logicsignalprotector.apitelegram.model.InlineKeyboard;
import com.logicsignalprotector.apitelegram.model.v2.ActionBlock;
import com.logicsignalprotector.apitelegram.model.v2.ActionItem;
import com.logicsignalprotector.apitelegram.model.v2.ChatResponseV2;
import com.logicsignalprotector.apitelegram.model.v2.ErrorBlock;
import com.logicsignalprotector.apitelegram.model.v2.ListBlock;
import com.logicsignalprotector.apitelegram.model.v2.NoticeBlock;
import com.logicsignalprotector.apitelegram.model.v2.ResponseBlock;
import com.logicsignalprotector.apitelegram.model.v2.Section;
import com.logicsignalprotector.apitelegram.model.v2.SectionsBlock;
import com.logicsignalprotector.apitelegram.model.v2.TableBlock;
import com.logicsignalprotector.apitelegram.model.v2.TextBlock;
import com.logicsignalprotector.apitelegram.model.v2.UiHintsV2;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelegramRendererV2 {

  private static final int ACTIONS_PER_ROW = 2;

  private final TelegramBotClient bot;
  private final FriendlyMessageTemplates templates;

  public TelegramRendererV2(TelegramBotClient bot, FriendlyMessageTemplates templates) {
    this.bot = bot;
    this.templates = templates;
  }

  public int render(
      String chatId,
      String sourceMessageId,
      boolean allowEdit,
      ChatResponseV2 response,
      String callbackData) {
    if (response == null || response.blocks() == null || response.blocks().isEmpty()) {
      return 0;
    }

    UiHintsV2 hints = response.uiHints();
    boolean preferEdit = hints != null && hints.preferEdit();
    boolean helpMenuPaging = isHelpMenuPagingCallback(callbackData);
    if (helpMenuPaging) {
      preferEdit = true;
    }
    boolean deleteSource = hints != null && hints.deleteSourceMessage();
    int sent = 0;
    boolean edited = false;
    PageContext pageContext = resolvePageContext(response, callbackData);
    boolean mergeDb = isDbCallback(callbackData);

    if (mergeDb) {
      RenderedMessage merged = renderDbComposite(response.blocks(), response.locale());
      if (merged == null || merged.text == null || merged.text.isBlank()) {
        return 0;
      }
      if (preferEdit && allowEdit && sourceMessageId != null) {
        bot.editMessageText(
            chatId, sourceMessageId, merged.text, merged.parseMode, merged.keyboard);
        edited = true;
      } else {
        bot.sendMessage(chatId, merged.text, merged.parseMode, merged.keyboard);
      }
      sent++;
      if (deleteSource && sourceMessageId != null) {
        bot.deleteMessage(chatId, sourceMessageId);
      }
      return sent;
    }

    List<RenderedMessage> messages = new ArrayList<>();
    InlineKeyboard pendingKeyboard = null;
    for (ResponseBlock block : response.blocks()) {
      if (helpMenuPaging && (block instanceof NoticeBlock || block instanceof ActionBlock)) {
        continue;
      }
      if (block instanceof ActionBlock actions) {
        InlineKeyboard keyboard = toKeyboard(actions.actions());
        if (keyboard == null) {
          continue;
        }
        if (!messages.isEmpty()) {
          RenderedMessage last = messages.remove(messages.size() - 1);
          InlineKeyboard merged = mergeKeyboards(last.keyboard, keyboard);
          messages.add(new RenderedMessage(last.text, last.parseMode, merged));
        } else {
          pendingKeyboard = mergeKeyboards(pendingKeyboard, keyboard);
        }
        continue;
      }
      RenderedMessage msg = renderBlock(block, response.locale(), pageContext);
      if (msg == null || msg.text == null || msg.text.isBlank()) {
        continue;
      }
      if (pendingKeyboard != null) {
        InlineKeyboard merged = mergeKeyboards(msg.keyboard, pendingKeyboard);
        messages.add(new RenderedMessage(msg.text, msg.parseMode, merged));
        pendingKeyboard = null;
      } else {
        messages.add(msg);
      }
    }

    for (RenderedMessage msg : messages) {
      if (preferEdit && allowEdit && !edited && sourceMessageId != null) {
        bot.editMessageText(chatId, sourceMessageId, msg.text, msg.parseMode, msg.keyboard);
        edited = true;
      } else {
        bot.sendMessage(chatId, msg.text, msg.parseMode, msg.keyboard);
      }
      sent++;
    }

    if (deleteSource && sourceMessageId != null) {
      bot.deleteMessage(chatId, sourceMessageId);
    }

    return sent;
  }

  private static boolean isHelpMenuPagingCallback(String callbackData) {
    if (callbackData == null || callbackData.isBlank()) {
      return false;
    }
    String data = callbackData.trim();
    return data.startsWith("h:") || data.startsWith("m:");
  }

  private static boolean isHelpMenuIntro(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    return "help_intro".equals(key) || "menu_intro".equals(key);
  }

  private static boolean isDbCallback(String callbackData) {
    if (callbackData == null || callbackData.isBlank()) {
      return false;
    }
    return callbackData.trim().startsWith("cmd:db:");
  }

  private static PageContext resolvePageContext(ChatResponseV2 response, String callbackData) {
    if (response == null) {
      return null;
    }
    PageContext fromCallback = parsePagingCallback(callbackData);
    String kind = fromCallback == null ? detectPagingKind(response.blocks()) : fromCallback.kind;
    if (kind == null) {
      return null;
    }
    int page = fromCallback == null ? 0 : fromCallback.page;
    String sessionId =
        fromCallback != null && fromCallback.sessionId != null && !fromCallback.sessionId.isBlank()
            ? fromCallback.sessionId
            : response.sessionId();
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    return new PageContext(kind, sessionId, page);
  }

  private static PageContext parsePagingCallback(String callbackData) {
    if (callbackData == null || callbackData.isBlank()) {
      return null;
    }
    String data = callbackData.trim();
    if (!data.startsWith("h:") && !data.startsWith("m:")) {
      return null;
    }
    String[] parts = data.split(":", 3);
    if (parts.length < 3) {
      return null;
    }
    Integer page = parseInt(parts[2]);
    if (page == null) {
      return null;
    }
    String sessionId = parts[1];
    return new PageContext(parts[0], sessionId, Math.max(0, page));
  }

  private static String detectPagingKind(List<ResponseBlock> blocks) {
    if (blocks == null || blocks.isEmpty()) {
      return null;
    }
    for (ResponseBlock block : blocks) {
      if (block instanceof NoticeBlock notice) {
        String key = notice.text();
        if ("help_intro".equals(key)) {
          return "h";
        }
        if ("menu_intro".equals(key)) {
          return "m";
        }
      }
    }
    return null;
  }

  private static Integer parseInt(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private RenderedMessage renderBlock(ResponseBlock block, String locale, PageContext pageContext) {
    if (block instanceof TextBlock text) {
      return new RenderedMessage(text.text(), null, null);
    }
    if (block instanceof NoticeBlock notice) {
      if (isHelpMenuIntro(notice.text())) {
        return null;
      }
      String resolved = templates.resolve(notice.text(), locale);
      return new RenderedMessage(resolved == null ? notice.text() : resolved, null, null);
    }
    if (block instanceof ListBlock list) {
      return new RenderedMessage(renderList(list.items()), null, null);
    }
    if (block instanceof SectionsBlock sections) {
      return renderSections(sections.sections(), pageContext);
    }
    if (block instanceof TableBlock table) {
      return renderTable(table);
    }
    if (block instanceof ErrorBlock error) {
      return renderError(error, locale);
    }
    return null;
  }

  private RenderedMessage renderDbComposite(List<ResponseBlock> blocks, String locale) {
    if (blocks == null || blocks.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    InlineKeyboard keyboard = null;
    for (ResponseBlock block : blocks) {
      if (block instanceof ActionBlock actions) {
        InlineKeyboard next = toKeyboard(actions.actions());
        keyboard = mergeKeyboards(keyboard, next);
        continue;
      }
      RenderedMessage msg = renderBlock(block, locale, null);
      if (msg == null || msg.text == null || msg.text.isBlank()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append("\n\n");
      }
      if ("HTML".equalsIgnoreCase(msg.parseMode)) {
        sb.append(msg.text);
      } else {
        sb.append(escapeHtml(msg.text));
      }
    }
    if (sb.length() == 0) {
      return null;
    }
    return new RenderedMessage(sb.toString(), "HTML", keyboard);
  }

  private RenderedMessage renderError(ErrorBlock error, String locale) {
    String message = error.message();
    if (message == null || message.isBlank()) {
      message = templates.resolve("error_default", locale);
    }
    String hint = error.hint();
    String text = message == null ? "" : message.trim();
    if (hint != null && !hint.isBlank()) {
      text = text + "\n" + hint.trim();
    }
    return new RenderedMessage(text, null, null);
  }

  private RenderedMessage renderTable(TableBlock table) {
    String format = table.format();
    if ("pretty".equalsIgnoreCase(format)) {
      return renderTablePretty(table);
    }
    return renderTablePlain(table);
  }

  private RenderedMessage renderTablePlain(TableBlock table) {
    List<String> columns = table.columns() == null ? List.of() : table.columns();
    List<List<String>> rows = table.rows() == null ? List.of() : table.rows();
    if (columns.isEmpty()) {
      return new RenderedMessage("", null, null);
    }

    int[] widths = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      widths[i] = safe(columns.get(i)).length();
    }
    for (List<String> row : rows) {
      if (row == null) continue;
      for (int i = 0; i < Math.min(row.size(), widths.length); i++) {
        widths[i] = Math.max(widths[i], safe(row.get(i)).length());
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append(renderRow(columns, widths));
    for (List<String> row : rows) {
      sb.append("\n").append(renderRow(row, widths));
    }

    String text = "<pre>" + escapeHtml(sb.toString()) + "</pre>";
    return new RenderedMessage(text, "HTML", null);
  }

  private RenderedMessage renderTablePretty(TableBlock table) {
    List<String> columns = table.columns() == null ? List.of() : table.columns();
    List<List<String>> rows = table.rows() == null ? List.of() : table.rows();
    if (columns.isEmpty()) {
      return new RenderedMessage("", null, null);
    }

    int[] widths = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      widths[i] = safe(columns.get(i)).length();
    }
    for (List<String> row : rows) {
      if (row == null) continue;
      for (int i = 0; i < Math.min(row.size(), widths.length); i++) {
        widths[i] = Math.max(widths[i], safe(row.get(i)).length());
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append(renderPrettyRow(columns, widths));
    sb.append("\n").append(renderPrettySeparator(widths));
    for (List<String> row : rows) {
      sb.append("\n").append(renderPrettyRow(row, widths));
    }

    String text = "<pre>" + escapeHtml(sb.toString()) + "</pre>";
    return new RenderedMessage(text, "HTML", null);
  }

  private static String renderRow(List<String> row, int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < widths.length; i++) {
      String value = i < row.size() ? safe(row.get(i)) : "";
      sb.append(padRight(value, widths[i]));
      if (i + 1 < widths.length) {
        sb.append("  ");
      }
    }
    return sb.toString();
  }

  private static String renderPrettyRow(List<String> row, int[] widths) {
    StringBuilder sb = new StringBuilder();
    sb.append("| ");
    for (int i = 0; i < widths.length; i++) {
      String value = i < row.size() ? safe(row.get(i)) : "";
      sb.append(padRight(value, widths[i]));
      sb.append(" |");
      if (i + 1 < widths.length) {
        sb.append(" ");
      }
    }
    return sb.toString();
  }

  private static String renderPrettySeparator(int[] widths) {
    StringBuilder sb = new StringBuilder();
    sb.append("| ");
    for (int i = 0; i < widths.length; i++) {
      int width = Math.max(1, widths[i]);
      sb.append(repeatChar('-', width));
      sb.append(" |");
      if (i + 1 < widths.length) {
        sb.append(" ");
      }
    }
    return sb.toString();
  }

  private static String renderList(List<String> items) {
    if (items == null || items.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (String item : items) {
      if (item == null || item.isBlank()) continue;
      if (sb.length() > 0) sb.append("\n");
      sb.append("- ").append(item.trim());
    }
    return sb.toString();
  }

  private RenderedMessage renderSections(List<Section> sections, PageContext pageContext) {
    if (sections == null || sections.isEmpty()) {
      return new RenderedMessage("", null, null);
    }
    List<Section> selected = sections;
    InlineKeyboard pager = null;
    if (pageContext != null && pageContext.kind != null && sections.size() > 1) {
      int idx = Math.max(0, Math.min(pageContext.page, sections.size() - 1));
      selected = List.of(sections.get(idx));
      pager = buildPagerKeyboard(pageContext.kind, pageContext.sessionId, idx, sections.size());
    }
    return new RenderedMessage(renderSectionsText(selected), null, pager);
  }

  private static String renderSectionsText(List<Section> sections) {
    if (sections == null || sections.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Section section : sections) {
      if (section == null) continue;
      if (sb.length() > 0) sb.append("\n\n");
      if (section.title() != null && !section.title().isBlank()) {
        sb.append(section.title().trim());
      }
      if (section.description() != null && !section.description().isBlank()) {
        sb.append("\n").append(section.description().trim());
      }
      if (section.items() != null && !section.items().isEmpty()) {
        sb.append("\n").append(renderList(section.items()));
      }
    }
    return sb.toString();
  }

  private static InlineKeyboard buildPagerKeyboard(
      String kind, String sessionId, int page, int totalPages) {
    if (kind == null || kind.isBlank() || sessionId == null || sessionId.isBlank()) {
      return null;
    }
    if (totalPages <= 1) {
      return null;
    }
    List<InlineKeyboard.Button> row = new ArrayList<>();
    if (page > 0) {
      row.add(new InlineKeyboard.Button("Назад", kind + ":" + sessionId + ":" + (page - 1)));
    }
    if (page + 1 < totalPages) {
      row.add(new InlineKeyboard.Button("Дальше", kind + ":" + sessionId + ":" + (page + 1)));
    }
    return row.isEmpty() ? null : new InlineKeyboard(List.of(row));
  }

  private static InlineKeyboard mergeKeyboards(InlineKeyboard first, InlineKeyboard second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    List<List<InlineKeyboard.Button>> rows = new ArrayList<>();
    if (first.rows() != null) {
      rows.addAll(first.rows());
    }
    if (second.rows() != null) {
      rows.addAll(second.rows());
    }
    return rows.isEmpty() ? null : new InlineKeyboard(rows);
  }

  private static InlineKeyboard toKeyboard(List<ActionItem> actions) {
    if (actions == null || actions.isEmpty()) {
      return null;
    }
    List<List<InlineKeyboard.Button>> rows = new ArrayList<>();
    List<InlineKeyboard.Button> row = new ArrayList<>();
    for (ActionItem action : actions) {
      if (action == null) continue;
      row.add(new InlineKeyboard.Button(safe(action.title()), safe(action.payload())));
      if (row.size() >= ACTIONS_PER_ROW) {
        rows.add(new ArrayList<>(row));
        row.clear();
      }
    }
    if (!row.isEmpty()) {
      rows.add(new ArrayList<>(row));
    }
    return rows.isEmpty() ? null : new InlineKeyboard(rows);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String padRight(String text, int width) {
    if (text.length() >= width) {
      return text;
    }
    StringBuilder sb = new StringBuilder(text);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  private static String repeatChar(char ch, int count) {
    if (count <= 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      sb.append(ch);
    }
    return sb.toString();
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    String out = s;
    out = out.replace("&", "&amp;");
    out = out.replace("<", "&lt;");
    out = out.replace(">", "&gt;");
    return out;
  }

  private static class PageContext {
    private final String kind;
    private final String sessionId;
    private final int page;

    private PageContext(String kind, String sessionId, int page) {
      this.kind = kind;
      this.sessionId = sessionId;
      this.page = page;
    }
  }

  private static class RenderedMessage {
    private final String text;
    private final String parseMode;
    private final InlineKeyboard keyboard;

    private RenderedMessage(String text, String parseMode, InlineKeyboard keyboard) {
      this.text = text;
      this.parseMode = parseMode;
      this.keyboard = keyboard;
    }
  }
}
