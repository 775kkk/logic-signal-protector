package com.logicsignalprotector.commandcenter.domain.v2;

import com.logicsignalprotector.commandcenter.api.dto.v2.Section;
import com.logicsignalprotector.commandcenter.api.dto.v2.SectionsBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MenuBuilder {

  private static final Set<String> ADMIN_PERMS =
      Set.of("ADMIN_USERS_PERMS_REVOKE", "COMMANDS_TOGGLE", "USERS_HARD_DELETE");

  public SectionsBlock build(Set<String> perms, boolean linked, boolean devConsoleEnabled) {
    List<String> items = new ArrayList<>();

    boolean canMarket = canMarket(perms, linked);
    String marketLine = "Работа с биржей: /market";
    if (!canMarket) {
      marketLine = marketLine + " (нет доступа)";
    }
    items.add(marketLine);

    if (canDev(perms, linked, devConsoleEnabled)) {
      items.add("Dev-работа с gateway и командами: /menu_dev");
      items.add("Работа с SQL: /db_menu");
    }

    items.add("Работа с аккаунтом: /menu_account");
    items.add("Общий список доступных команд: /help");

    Section section = new Section("Основные инструменты работы:", null, items);
    return new SectionsBlock(List.of(section));
  }

  public SectionsBlock buildMarketMenu() {
    List<Section> sections = new ArrayList<>();
    sections.add(buildMarketGuideSection());
    return new SectionsBlock(sections);
  }

  private static Section buildMarketGuideSection() {
    List<String> items = new ArrayList<>();
    items.add("Найди тикер через /market_instruments (можно с фильтром)");
    items.add("Запроси цену: /market_quote *Id*");
    items.add("Свечи: /market_candles *Id* interval=60 limit=10");
    items.add("Глубина: /market_orderbook *Id* depth=10");
    items.add("Сделки: /market_trades *Id* limit=10");
    return new Section("Памятка", "Команды доступны в форме /market_*.", items);
  }

  public SectionsBlock buildAccountMenu() {
    List<String> items = new ArrayList<>();
    items.add("/login - логин + привязка");
    items.add("/register - регистрация + привязка");
    items.add("/logout - отвязка (с подтверждением)");
    items.add("/me - статус привязки");
    Section section = new Section("Работа с аккаунтом", null, items);
    return new SectionsBlock(List.of(section));
  }

  public SectionsBlock buildDevMenu(boolean devConsoleEnabled) {
    List<String> items = new ArrayList<>();
    if (devConsoleEnabled) {
      items.add("/adminlogin <code> - dev-логин ADMIN");
    }
    items.add("/db_menu - меню доступа к БД");
    items.add("/db <SQL> - выполнить SQL в gateway DB");
    items.add("/db_tables - список таблиц");
    items.add("/db_describe <schema.table> - структура таблицы");
    items.add("/db_history - flyway_schema_history");
    items.add("/users - список пользователей");
    items.add("/user <login> - карточка пользователя");
    items.add("/roles - список ролей");
    items.add("/perms - список прав");
    items.add("/commands - список команд и статусов");
    items.add("/command enable|disable <code> - включить/выключить команду");
    items.add("/user_delete <login|id> - hard delete пользователя");
    Section section = new Section("Dev-работа с gateway", null, items);
    return new SectionsBlock(List.of(section));
  }

  public boolean canDev(Set<String> perms, boolean linked, boolean devConsoleEnabled) {
    if (!linked || perms == null || perms.isEmpty()) {
      return false;
    }
    if (perms.contains("DEVGOD")) {
      return true;
    }
    for (String perm : perms) {
      if (ADMIN_PERMS.contains(perm)) {
        return true;
      }
    }
    return false;
  }

  public boolean canMarket(Set<String> perms, boolean linked) {
    if (!linked || perms == null) {
      return false;
    }
    return perms.contains("MARKETDATA_READ");
  }
}
