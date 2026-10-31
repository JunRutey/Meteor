package com.meteor.memory.feature.ai

/** What a skill is for: distilling a persona, or talking with one. */
enum class SkillStage(val wire: String) {
    DISTILL("distill"),
    CHAT("chat"),
    ;

    companion object {
        fun fromWire(value: String): SkillStage? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One bundled Markdown skill: YAML-ish frontmatter plus a prompt body.
 *
 * Format follows the MIT-licensed skill packages this module adapts ideas from
 * (nuwa-skill / distilly / ex-skill): a directory of Markdown, frontmatter for
 * discovery, and a body the agent reads as instructions (ADR-024).
 */
data class AiSkill(
    val name: String,
    val description: String,
    val version: String,
    val stage: SkillStage,
    val requiresPerson: Boolean,
    val body: String,
    val source: String,
)

/** Frontmatter parser. Hand-rolled: the header is a handful of scalar keys. */
object SkillMarkdown {

    fun parse(text: String, source: String): AiSkill? {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.firstOrNull()?.trim() != FENCE) return null

        val closing = (1 until lines.size).firstOrNull { lines[it].trim() == FENCE } ?: return null
        val header = LinkedHashMap<String, String>()
        for (line in lines.subList(1, closing)) {
            if (line.isBlank() || line.trim().startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            header[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim().removeSurrounding("\"")
        }

        val name = header[KEY_NAME]?.takeIf { it.isNotBlank() } ?: return null
        val stage = SkillStage.fromWire(header[KEY_STAGE].orEmpty()) ?: return null
        val body = lines.drop(closing + 1).joinToString("\n").trim()
        if (body.isBlank()) return null

        return AiSkill(
            name = name,
            description = header[KEY_DESCRIPTION].orEmpty(),
            version = header[KEY_VERSION] ?: "1.0.0",
            stage = stage,
            requiresPerson = header[KEY_REQUIRES_PERSON]?.equals("true", ignoreCase = true) == true,
            body = body,
            source = source,
        )
    }

    private const val FENCE = "---"
    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_VERSION = "version"
    private const val KEY_STAGE = "stage"
    private const val KEY_REQUIRES_PERSON = "requires-person"
}

/**
 * Bundled skills. Assets are read through [loader] so `:feature-ai` stays free
 * of an Android `Context` and the parser stays unit-testable.
 */
class SkillLibrary(private val loader: (String) -> String?) {

    private var cache: List<AiSkill>? = null

    fun all(): List<AiSkill> = cache ?: BUNDLED
        .mapNotNull { path -> loader(path)?.let { SkillMarkdown.parse(it, path) } }
        .also { cache = it }

    fun chatSkills(): List<AiSkill> = all().filter { it.stage == SkillStage.CHAT }

    fun find(name: String): AiSkill? = all().firstOrNull { it.name == name }

    companion object {
        val BUNDLED: List<String> = listOf(
            "skills/persona-distill.md",
            "skills/persona-chat.md",
            "skills/archive-qa.md",
        )
    }
}
