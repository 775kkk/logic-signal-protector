package com.logicsignalprotector.commandcenter.api.dto.v2;

import java.util.List;

public record ChatResponseV2(
    List<ResponseBlock> blocks,
    String correlationId,
    String sessionId,
    String locale,
    UiHintsV2 uiHints) {}
