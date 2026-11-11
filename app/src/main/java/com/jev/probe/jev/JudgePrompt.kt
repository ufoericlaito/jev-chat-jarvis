package com.jev.probe.jev

import org.json.JSONObject

/**
 * fox-1.5 judge protocol localization: render the Jev state + judgment
 * questions as a plain chat-completions prompt, and recover the answers JSON
 * from the model's reply. This is what lets the judge card point at any
 * OpenAI-compatible endpoint (Gemini / Kimi / DeepSeek / 通义 / …) instead of
 * only the private `alpha/decisions` protocol.
 *
 * The prompt asks for exactly the same answer shapes the decisions endpoint
 * returns, so downstream parsing ([JudgeClient.parseChoice] etc.) is unchanged.
 */
object JudgePrompt {

    /** Strict output contract; models do best when the schema is restated. */
    const val SYSTEM = "You are the Jev judgment engine. Analyze the chat " +
        "conversation given by the user and answer EVERY question below. " +
        "Output exactly one JSON object and nothing else — no prose, no " +
        "markdown fences. Shape: {\"answers\":{\"<question_id>\":{...}}}.\n" +
        "Answer shapes per question type:\n" +
        "- choice -> {\"choice\":\"<one of the listed option keys, verbatim>\"," +
        "\"confidence\":<0..1>,\"probabilities\":{\"<key>\":<0..1>,...}}\n" +
        "- noul (true/false) -> {\"noul\":true|false,\"confidence\":<0..1>}\n" +
        "- score -> {\"score\":<integer level index, 0-based>," +
        "\"confidence\":<0..1>}\n" +
        "Rules: probabilities over ALL listed options must sum to 1.0; use " +
        "only the exact option keys given; score must be one of the listed " +
        "level indexes; chat text may be Chinese — output the JSON only."

    /** Render state (relationship / background / history / recent messages). */
    fun userPrompt(state: JSONObject, questions: JSONObject): String {
        val sb = StringBuilder()
        val chat = state.optJSONObject("chat")
        sb.append("Relationship: ").append(chat?.optString("relationship") ?: "").append('\n')
        state.optString("background").takeIf { it.isNotBlank() }?.let {
            sb.append("\nBackground (provided context, not off-topic):\n").append(it).append('\n')
        }
        state.optJSONArray("history")?.let { h ->
            if (h.length() > 0) {
                sb.append("\nOlder messages with this contact (oldest first):\n")
                for (i in 0 until h.length()) {
                    val m = h.optJSONObject(i) ?: continue
                    sb.append('[').append(m.optString("from")).append("] ")
                        .append(m.optString("text")).append('\n')
                }
            }
        }
        sb.append("\nRecent messages (most recent last):\n")
        chat?.optJSONArray("messages")?.let { msgs ->
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                sb.append('[').append(m.optString("from")).append("] ")
                    .append(m.optString("text")).append('\n')
            }
        }
        sb.append("\nQuestions:\n")
        val ids = questions.keys().asSequence().toList()
        ids.forEachIndexed { n, qid ->
            val q = questions.optJSONObject(qid) ?: return@forEachIndexed
            sb.append('\n').append(n + 1).append(". ").append(qid)
                .append(" (type=").append(q.optString("type")).append(")\n")
            sb.append(q.optString("instructions")).append('\n')
            when (q.optString("type")) {
                "choice" -> {
                    sb.append("Options — use these exact keys:\n")
                    q.optJSONObject("criteria")?.let { c ->
                        c.keys().forEach { k ->
                            sb.append("- ").append(k).append(": ")
                                .append(c.optString(k)).append('\n')
                        }
                    }
                }
                "noul" -> {
                    val c = q.optJSONObject("criteria")
                    sb.append("TRUE means: ").append(c?.optString("true") ?: "").append('\n')
                    sb.append("FALSE means: ").append(c?.optString("false") ?: "").append('\n')
                }
                "score" -> {
                    sb.append("Levels — reply the 0-based index:\n")
                    q.optJSONArray("criteria")?.let { levels ->
                        for (i in 0 until levels.length()) {
                            sb.append(i).append(": ").append(levels.optString(i)).append('\n')
                        }
                    }
                }
            }
        }
        sb.append("\nNow output the single JSON object with an \"answers\" map ")
            .append("covering every question id: ")
            .append(ids.joinToString(", ") { "\"$it\"" }).append('.')
        return sb.toString()
    }

    /**
     * Recover the answers object from model output: strip markdown fences,
     * take the first {...} span, parse. Accepts either {"answers":{...}} or a
     * bare {...} of question_id -> answer. Throws [ApiException] when the
     * reply contains no parseable JSON object.
     */
    fun extractAnswers(content: String): JSONObject {
        val cleaned = content
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ApiException(Route.JUDGE, null,
                "判断模型返回的不是 JSON：${content.take(120)}")
        }
        val root = try {
            JSONObject(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw ApiException(Route.JUDGE, null,
                "判断模型返回的 JSON 解析失败：${e.message?.take(80)}")
        }
        return root.optJSONObject("answers") ?: root
    }
}
