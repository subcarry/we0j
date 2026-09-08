package com.we0j.server.dto;

import java.util.List;

/** provider 配置行（GET /providers、POST /providers/{id} 响应；apiKey 脱敏）。 */
public record ProviderDto(String id, boolean enabled, String apiBase, String apiKeyMasked,
                          String family, List<String> models) {
}
