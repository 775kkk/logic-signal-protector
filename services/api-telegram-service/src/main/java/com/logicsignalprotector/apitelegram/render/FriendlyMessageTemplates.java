package com.logicsignalprotector.apitelegram.render;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "friendly")
public class FriendlyMessageTemplates {

  private Map<String, String> templates = new HashMap<>();
  private Map<String, Map<String, String>> locales = new HashMap<>();

  public String resolve(String key, String locale) {
    if (key == null || key.isBlank()) {
      return null;
    }
    if (locale != null && locales.containsKey(locale)) {
      String localized = locales.get(locale).get(key);
      if (localized != null && !localized.isBlank()) {
        return localized;
      }
    }
    String fallback = templates.get(key);
    return fallback == null || fallback.isBlank() ? null : fallback;
  }

  public String format(String key, String locale, Object... args) {
    String template = resolve(key, locale);
    if (template == null) {
      return null;
    }
    try {
      return String.format(template, args == null ? new Object[0] : args);
    } catch (Exception e) {
      return template;
    }
  }

  public Map<String, String> getTemplates() {
    return templates;
  }

  public void setTemplates(Map<String, String> templates) {
    this.templates = templates == null ? new HashMap<>() : templates;
  }

  public Map<String, Map<String, String>> getLocales() {
    return locales;
  }

  public void setLocales(Map<String, Map<String, String>> locales) {
    this.locales = locales == null ? new HashMap<>() : locales;
  }
}
