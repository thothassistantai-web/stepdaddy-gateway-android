package com.nova.stepdaddylivehd.gateway.ui.player

object HlsErrorManifestParser {
  private val stepDaddyComment = Regex("""#\s*StepDaddy:\s*(.+)""", RegexOption.IGNORE_CASE)

  fun extractMessage(body: String): String? =
      stepDaddyComment.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

  fun isErrorManifest(body: String): Boolean = extractMessage(body) != null
}
