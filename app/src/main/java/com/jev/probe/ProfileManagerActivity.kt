package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs
import com.jev.probe.core.Profile
import com.jev.probe.core.ProfileStore

/**
 * fox-1.6: relationship-profile manager. Lists every profile with quick
 * actions (activate / edit / duplicate / export / delete), plus create-blank,
 * import-from-clipboard and a reset-to-presets escape hatch.
 */
class ProfileManagerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private lateinit var listBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = ProfileStore(prefs)
        store.ensurePresets()

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F3F5"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.addView(box)

        box.addView(TextView(this).apply {
            text = "关系人设管理"
            textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        })
        box.addView(TextView(this).apply {
            text = "分析时按「当前人设」注入关系与风格；会话标题命中某套白名单会自动切换。导出=复制 JSON 模板，发给别人导入即用。"
            textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(6), 0, dp(12))
        })

        // Top action row
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(actionBtn("＋ 新建空白") { editDialog(null) })
        actions.addView(actionBtn("📥 导入剪贴板") { importFromClipboard() })
        actions.addView(actionBtn("♻ 重置预置") { confirmResetPresets() })
        box.addView(actions)

        // Active-profile indicator
        box.addView(TextView(this).apply {
            val a = store.active()
            text = "当前生效：" + (a?.name ?: "默认人设（未选）")
            textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3A7AFE"))
            setPadding(0, dp(14), 0, dp(8))
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listBox)

        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ------------------------------------------------------------- list

    private fun refreshList() {
        listBox.removeAllViews()
        val all = store.all()
        if (all.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有人设，点上方「新建」或「重置预置」拿 7 套模板。"
                textSize = 13f; setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(20), 0, 0)
            })
            return
        }
        val activeId = prefs.activeProfileId
        all.forEach { p ->
            listBox.addView(profileCard(p, p.id == activeId))
        }
    }

    private fun profileCard(p: Profile, isActive: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor(if (isActive) "#3A7AFE" else "#E5E7EB"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = p.name + if (isActive) " ✓当前" else ""
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isActive) "#3A7AFE" else "#111827"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (p.whitelistKeys().isNotEmpty()) {
            head.addView(TextView(this).apply {
                text = "自动:" + p.whitelistKeys().joinToString("/")
                textSize = 10f; setTextColor(Color.parseColor("#9CA3AF"))
            })
        }
        card.addView(head)

        card.addView(TextView(this).apply {
            text = "关系：" + clip(p.relationship, 60)
            textSize = 12f; setTextColor(Color.parseColor("#4B5563"))
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "风格：" + clip(p.style, 60)
            textSize = 12f; setTextColor(Color.parseColor("#4B5563"))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(miniBtn(if (isActive) "取消激活" else "设为当前") {
            store.setActive(if (isActive) null else p.id)
            toast(if (isActive) "已切回默认人设" else "已激活：${p.name}")
            refreshList()
        })
        row.addView(miniBtn("编辑") { editDialog(p) })
        row.addView(miniBtn("复制") {
            store.duplicate(p.id); toast("已复制"); refreshList()
        })
        row.addView(miniBtn("导出") { exportProfile(p) })
        row.addView(miniBtn("删除") { confirmDelete(p) })
        card.addView(row)
        return card
    }

    // ------------------------------------------------------------- dialogs

    /** Null [p] means create-new. */
    private fun editDialog(p: Profile?) {
        val isNew = p == null
        val target = p ?: Profile(
            id = java.util.UUID.randomUUID().toString(),
            name = "", relationship = "", style = ""
        )

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameEt = formField(form, "名称", target.name, "如：职场模式")
        val relEt = formField(form, "关系描述（对方是谁）", target.relationship,
            "如：对方是我的直属领导，对话以工作为主", multiLine = true)
        val styleEt = formField(form, "风格指令（怎么回）", target.style,
            "如：正式克制、分点陈述、不用表情包", multiLine = true)
        val wlEt = formField(form, "自动匹配白名单（可空）", target.whitelist,
            "会话标题关键词，逗号分隔。命中则自动切本套", multiLine = true)

        AlertDialog.Builder(this)
            .setTitle(if (isNew) "新建人设" else "编辑：${target.name}")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                target.name = nameEt.text.toString().trim().ifBlank { "未命名" }
                target.relationship = relEt.text.toString()
                target.style = styleEt.text.toString()
                target.whitelist = wlEt.text.toString()
                store.upsert(target)
                toast("已保存")
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formField(
        parent: LinearLayout, label: String, value: String, hint: String, multiLine: Boolean = false
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(10), 0, 0)
        })
        val et = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP
            }
        }
        parent.addView(et)
        return et
    }

    private fun importFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) { toast("剪贴板是空的"); return }
        val p = try {
            store.import(text)
        } catch (e: Exception) {
            null
        }
        if (p != null) { toast("已导入：${p.name}"); refreshList() }
        else toast("导入失败：不是有效的 fox_profile_v1 JSON")
    }

    private fun exportProfile(p: Profile) {
        val json = store.export(p)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(p.name, json))
        toast("「${p.name}」JSON 已复制，直接粘贴分享即可")
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("删除「${p.name}」？")
            .setMessage("不可恢复。导出可先备份。")
            .setPositiveButton("删除") { _, _ ->
                store.delete(p.id); toast("已删除"); refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmResetPresets() {
        AlertDialog.Builder(this)
            .setTitle("重置为 7 套预置模板？")
            .setMessage("会清空你现有的全部人设（含自建），重新种入预置。建议先导出备份。")
            .setPositiveButton("重置") { _, _ ->
                prefs.profilesJson = ""
                prefs.activeProfileId = ""
                store.ensurePresets()
                toast("已重置为预置 7 套")
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------- widgets

    private fun actionBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
        }
    }

    private fun miniBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; textSize = 12f
            setTextColor(Color.parseColor("#3A7AFE"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun clip(s: String, n: Int): String {
        val t = s.replace('\n', ' ')
        return if (t.length <= n) t else t.take(n) + "…"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
