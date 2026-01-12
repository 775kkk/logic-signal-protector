package com.logicsignalprotector.commandcenter.domain.v2;

import com.logicsignalprotector.commandcenter.api.dto.v2.Section;
import com.logicsignalprotector.commandcenter.api.dto.v2.SectionsBlock;
import com.logicsignalprotector.commandcenter.domain.CommandRegistry;
import com.logicsignalprotector.commandcenter.domain.CommandRegistry.CommandDef;
import com.logicsignalprotector.commandcenter.domain.CommandSwitchCache;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HelpBuilder {

  private static final Set<String> ADMIN_PERMS =
      Set.of("ADMIN_USERS_PERMS_REVOKE", "COMMANDS_TOGGLE", "USERS_HARD_DELETE");

  private final CommandRegistry registry;
  private final CommandSwitchCache switches;

  public HelpBuilder(CommandRegistry registry, CommandSwitchCache switches) {
    this.registry = registry;
    this.switches = switches;
  }

  public SectionsBlock build(Set<String> perms, boolean linked, boolean devConsoleEnabled) {
    Map<HelpSection, List<String>> items = new EnumMap<>(HelpSection.class);
    for (HelpSection section : HelpSection.values()) {
      items.put(section, new ArrayList<>());
    }

    for (CommandDef def : registry.all()) {
      if (!def.showInHelp()) {
        continue;
      }
      if (def.devOnly() && !devConsoleEnabled) {
        continue;
      }
      if (def.toggleable() && !switches.isEnabled(def.code())) {
        continue;
      }
      if (!isPermitted(def, perms, linked)) {
        continue;
      }

      HelpSection section = classify(def);
      items.get(section).add(formatItem(def));
    }
    ensureMenuItem(items);

    List<Section> sections = new ArrayList<>();
    for (HelpSection section : HelpSection.values()) {
      List<String> sectionItems = items.get(section);
      if (sectionItems == null || sectionItems.isEmpty()) {
        continue;
      }
      sections.add(new Section(section.title, section.description, sectionItems));
    }

    return new SectionsBlock(sections);
  }

  private static boolean isPermitted(CommandDef def, Set<String> perms, boolean linked) {
    if ((!def.requiredAllPerms().isEmpty() || !def.requiredAnyPerms().isEmpty()) && !linked) {
      return false;
    }
    if (!def.requiredAllPerms().isEmpty() && !perms.containsAll(def.requiredAllPerms())) {
      return false;
    }
    if (!def.requiredAnyPerms().isEmpty()) {
      for (String p : def.requiredAnyPerms()) {
        if (perms.contains(p)) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private static HelpSection classify(CommandDef def) {
    if (def.devOnly() || def.requiredAnyPerms().contains("DEVGOD")) {
      return HelpSection.DEV;
    }
    if (isAdmin(def)) {
      return HelpSection.ADMIN;
    }
    return HelpSection.MAIN;
  }

  private static boolean isAdmin(CommandDef def) {
    if (def.code() != null && def.code().startsWith("admin")) {
      return true;
    }
    Set<String> required = new LinkedHashSet<>();
    required.addAll(def.requiredAllPerms());
    required.addAll(def.requiredAnyPerms());
    for (String perm : required) {
      if (ADMIN_PERMS.contains(perm)) {
        return true;
      }
    }
    return false;
  }

  private static String formatItem(CommandDef def) {
    return def.command() + " - " + def.description();
  }

  private static void ensureMenuItem(Map<HelpSection, List<String>> items) {
    List<String> main = items.get(HelpSection.MAIN);
    if (main == null) {
      return;
    }
    boolean exists = main.stream().anyMatch(item -> item.startsWith("/menu "));
    if (!exists) {
      main.add("/menu - навигация");
    }
  }

  private enum HelpSection {
    MAIN("Основные", "базовые команды"),
    ADMIN("Администратор", "управление доступами и пользователями"),
    DEV("Dev", "служебные команды");

    private final String title;
    private final String description;

    HelpSection(String title, String description) {
      this.title = title;
      this.description = description;
    }
  }
}
