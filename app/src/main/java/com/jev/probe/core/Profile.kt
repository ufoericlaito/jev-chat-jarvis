package com.jev.probe.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * fox-1.6: A switchable relationship persona. Each profile bundles a
 * relationship description (who the other person is) with a style directive
 * (how replies should read). The active profile's [prompt] replaces the
 * single global Prefs.relationship in both the judge and the reply calls.
 *
 * Profiles can be created, edited, duplicated, deleted, exported as JSON
 * (share/backup) and imported back. Preset templates ship with the app.
 */
data class Profile(
    val id: String,
    var name: String,
    var relationship: String,
    var style: String,
    var whitelist: String = ""
) {
    /** Combined text injected into judge/reply calls. Blank-safe. */
    fun prompt(): String {
        val rel = relationship.trim()
        val st = style.trim()
        return when {
            rel.isNotEmpty() && st.isNotEmpty() -> "$rel\n【回复风格】$st"
            rel.isNotEmpty() -> rel
            else -> st
        }
    }

    /** Per-profile conversation titles (comma / newline separated). */
    fun whitelistKeys(): Set<String> =
        whitelist.split('\n', '，', ',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("id", id)
        .put("name", name)
        .put("relationship", relationship)
        .put("style", style)
        .put("whitelist", whitelist)

    companion object {
        const val FORMAT = "fox_profile_v1"

        fun fromJson(o: JSONObject): Profile? = try {
            Profile(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "未命名" },
                relationship = o.optString("relationship"),
                style = o.optString("style"),
                whitelist = o.optString("whitelist")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * JSON-array persistence over [Prefs]' SharedPreferences, plus the active
 * profile pointer and the preset seeding on first run.
 */
class ProfileStore(private val prefs: Prefs) {

    fun all(): List<Profile> {
        val raw = prefs.profilesJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Profile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun active(): Profile? {
        val id = prefs.activeProfileId
        if (id.isBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    fun setActive(id: String?) {
        prefs.activeProfileId = id ?: ""
    }

    fun byId(id: String): Profile? = all().firstOrNull { it.id == id }

    fun upsert(p: Profile) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        save(list)
    }

    fun delete(id: String) {
        save(all().filterNot { it.id == id })
        if (prefs.activeProfileId == id) prefs.activeProfileId = ""
    }

    fun duplicate(id: String): Profile? {
        val src = byId(id) ?: return null
        val copy = Profile(
            id = UUID.randomUUID().toString(),
            name = src.name + " 副本",
            relationship = src.relationship,
            style = src.style,
            whitelist = src.whitelist
        )
        upsert(copy)
        return copy
    }

    /** One profile → shareable JSON text. */
    fun export(p: Profile): String = p.toJson().toString(2)

    /** Parse + store an imported profile; returns it on success. */
    fun import(json: String): Profile? {
        val p = Profile.fromJson(JSONObject(json)) ?: return null
        // Always mint a fresh id on import so it never clobbers a local one.
        val fresh = p.copy(id = UUID.randomUUID().toString())
        upsert(fresh)
        return fresh
    }

    private fun save(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.profilesJson = arr.toString()
    }

    /** Seed the 7 preset templates on first launch (idempotent). */
    fun ensurePresets() {
        if (prefs.profilesJson.isNotBlank()) return
        save(PRESETS.map {
            Profile(
                id = UUID.randomUUID().toString(),
                name = it.first,
                relationship = it.second,
                style = it.third
            )
        })
    }

    companion object {
        /**
         * 7 bundled presets. relationship = background for the judge;
         * style = reply-tone directive.
         */
        val PRESETS: List<Triple<String, String, String>> = listOf(
            Triple(
                "职场模式",
                "对方是我的职场联系人（领导/同事/合作方）。对话以工作事务为主，需要专业可靠的回应。",
                "专业、简洁、有条理。陈述句为主，少反问。不卑不亢——对上尊重不谄媚，对平级友好有分寸。不用 emoji 和网络梗，不抱怨不情绪化。通常 1-3 句说完整即止，复杂事项分点（1. 2. 3.）。"
            ),
            Triple(
                "死党互损",
                "对方是多年好友，关系铁到可以互怼。日常闲聊、游戏开黑、分享沙雕内容。",
                "轻松沙雕，像损友聊天。口语化短句，能接梗就接（别硬塞），偶尔夸张反问“不是哥们？？？”。可以损对方但不下狠手。铁律：对方认真时立刻收梗认真回；涉及钱和感情伤口的话题自动切换正经模式。一两句搞定，不写小作文。"
            ),
            Triple(
                "恋人模式",
                "对方是我的恋人，我们在稳定交往。对话以日常分享、情绪陪伴为主，重点是让对方感到被在乎。",
                "温柔体贴，先接情绪再回应事情。自然口语，可以撒娇可以宠，对方抛话题必须接住并回抛。禁忌：不敷衍（“哦”“嗯”单独发送禁止）、不冷暴力、不提前任、不评价外貌缺点。长度跟对方节奏走。"
            ),
            Triple(
                "撩妹模式",
                "对方是我正在追求或暧昧中的女生。我需要主动制造好感、推进关系，但不能油腻不能舔。",
                "会夸——夸具体不夸泛泛（“你今天那个观点很 sharp”好过“你好美”）。会接——她抛的每个话题都接住并轻轻回抛一个，让对话一直有来有回。会撩——适度幽默加轻微暧昧，点到为止，留白让她接。三条铁律：①不土味情话轰炸，一条对话最多一处撩；②不舔狗——对方回得慢就缓，不连发不追问“在吗”；③尊重节奏，不越界不开黄腔。她认真聊事时收掉嬉皮认真回。长度一两句为主，偶尔认真时可以稍长。"
            ),
            Triple(
                "家人亲情",
                "对方是我的父母/长辈。他们关心我的近况，容易唠叨，看到消息会反复读。",
                "耐心、温暖、懂事。多关心多报备，被问近况挑好的说但别假。被唠叨时不反驳先应着，最高优先级是让父母安心。铁律：重大坏消息不当微信说（约见面讲）；不嫌烦、不用表情包敷衍。长度适中，别太长让父母看着累。"
            ),
            Triple(
                "客户商务",
                "对方是甲方/客户/商务伙伴。对话直接影响合作与收入，每个字都要稳。",
                "礼貌开头（X总/X老师），专业可靠。不确定的事说“我确认后回复您”，不说“不知道”。不用语气词（哈/呀/哦）和表情包。承诺留余地——写“预计周三前”不写“周三一定”。不超 5 句，说清楚为准。"
            ),
            Triple(
                "网友新朋友",
                "对方是社交平台（Soul/微信群等）新认识的网友，关系浅，边界感重要。",
                "友好开朗但保持边界。多问对方少说自己，接梗但不硬接。好奇但不查户口。铁律：不透露真实姓名/住址/公司/收入，不牵涉金钱，深夜不聊感情话题，对方没回别连发。一两句留接话空间。"
            )
        )
    }
}
