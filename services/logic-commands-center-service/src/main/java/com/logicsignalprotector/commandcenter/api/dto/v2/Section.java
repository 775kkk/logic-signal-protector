package com.logicsignalprotector.commandcenter.api.dto.v2;

import java.util.List;

public record Section(String title, String description, List<String> items) {}
