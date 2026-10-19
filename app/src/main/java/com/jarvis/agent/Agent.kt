package com.jarvis.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The brain loop: read screen -> ask Gemini for ONE action -> execute -> repeat until done. */
class Agent(private val ctx: Context, private val io: AgentIO) {

    interface AgentIO {
        fun status(text: String)
        suspend fun speak(text: String)
        suspend fun confirm(question: String): Boolean
    }

    private var wantShot = false

    suspend fun run(goal: String) = withContext(Dispatchers.IO) {
        val apiKey = Prefs.get(ctx, Prefs.GEMINI_KEY)
        if (apiKey.isBlank()) {
            io.speak("Please add your Gemini API key in the Jarvis app.")
            return@withContext
        }
        val model = Prefs.get(ctx, Prefs.MODEL, "gemini-2.5-flash")
        val maxSteps = Prefs.get(ctx, Prefs.MAX_STEPS, "25").toIntOrNull() ?: 25
        val replyLang = Prefs.get(ctx, Prefs.REPLY_LANG, "English")
        val system = SYSTEM.replace("{LANG}", replyLang)
        val history = ArrayList<String>()

        for (step in 1..maxSteps) {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                io.speak("The accessibility service is off. Please enable Jarvis in Accessibility settings.")
                return@withContext
            }
            delay(300) // let the UI settle
            val snap = svc.snapshot()
            var shot: String? = null
            if (wantShot || snap.interactive < 3) shot = svc.screenshot()
            wantShot = false

            val hist = if (history.isEmpty()) "(none yet)" else history.takeLast(15).joinToString("\n")
            val prompt = buildString {
                append("GOAL: ").append(goal).append("\n\n")
                append("CURRENT APP: ").append(snap.pkg).append("\n\n")
                append("ACTIONS SO FAR:\n").append(hist).append("\n\n")
                append("SCREEN ELEMENTS ([index] type \"label\" flags @x,y on a 0-1000 grid):\n")
                append(snap.text)
                if (shot != null) append("\nA screenshot of the screen is attached. tap_xy/swipe_xy use the same 0-1000 grid.")
            }
            io.status("Step $step/$maxSteps")

            val call = GeminiClient.step(apiKey, model, system, prompt, shot, TOOLS)
            if (call.name == "done") {
                io.speak(call.args.optString("message", "Done."))
                return@withContext
            }
            io.status("${call.name} ${call.args}")
            val result = try {
                exec(svc, call)
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            history.add("$step. ${call.name}(${call.args}) -> $result")
            if (history.size >= 3 &&
                history.takeLast(3).map { it.substringAfter(". ") }.distinct().size == 1
            ) {
                history.add("NOTE: You are repeating the same action with no progress. Try something different.")
            }
        }
        io.speak("I reached the step limit before finishing.")
    }

    private suspend fun exec(svc: JarvisAccessibilityService, c: GeminiClient.Call): String {
        val a = c.args
        return when (c.name) {
            "open_app" -> openApp(a.optString("app_name"))
            "click" -> svc.click(a.getInt("index"), false)
            "long_click" -> svc.click(a.getInt("index"), true)
            "type_text" -> svc.typeText(if (a.has("index")) a.getInt("index") else null, a.optString("text"))
            "scroll" -> svc.scroll(a.optString("direction", "down"))
            "press_key" -> svc.pressKey(a.optString("key"))
            "tap_xy" -> svc.tapNorm(a.getDouble("x").toFloat(), a.getDouble("y").toFloat())
            "swipe_xy" -> svc.swipeNorm(
                a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(),
                a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat()
            )
            "call_number" -> callNumber(a.optString("number"))
            "wait" -> {
                delay((a.optDouble("seconds", 2.0).coerceIn(0.5, 10.0) * 1000).toLong())
                "Waited"
            }
            "confirm" -> {
                if (io.confirm(a.optString("question", "Shall I continue?"))) "User said YES"
                else "User said NO - do not proceed with that action"
            }
            "request_screenshot" -> {
                wantShot = true
                "A screenshot will be attached on the next step"
            }
            else -> "Unknown tool ${c.name}"
        }
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val q = name.lowercase().trim()
        val match = apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }
            ?: apps.firstOrNull { q.contains(it.first.lowercase()) }
        if (match == null) {
            return "No app named '$name'. Installed apps: " +
                apps.map { it.first }.sorted().take(60).joinToString()
        }
        val li = pm.getLaunchIntentForPackage(match.second) ?: return "Cannot launch ${match.first}"
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(li)
        return "Launched ${match.first}"
    }

    private fun callNumber(number: String): String {
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return if (granted) "Calling $number" else "Opened dialer for $number (call permission not granted)"
    }

    companion object {
        private const val SYSTEM = """You are Jarvis, an AI agent that operates an Android phone for its owner.
Each turn you receive the goal, the actions taken so far, and the CURRENT screen as a numbered list of UI elements (sometimes with a screenshot). Call exactly ONE tool per turn.
Rules:
- Prefer click(index) using the element list. Use tap_xy only when the needed element is not in the list (then a screenshot is attached, or call request_screenshot).
- type_text replaces the content of an input field. After typing a search, use press_key enter or click the search button.
- To find something off-screen, use scroll. "down" reveals content further down.
- If the goal needs no phone interaction (a question, chit-chat), call done immediately with the answer.
- Before sending a message, making a payment, deleting anything, posting publicly, or placing a call, you MUST call confirm first.
- Never type passwords, PINs or OTPs. Ask the user to do it themselves via done.
- If an action had no visible effect, try a different approach instead of repeating it.
- When finished, call done with a short spoken-style result (1-2 sentences). Write that final message in: {LANG}."""

        private fun p(name: String, type: String, desc: String) = Triple(name, type, desc)

        private fun fn(
            name: String,
            desc: String,
            params: List<Triple<String, String, String>> = emptyList(),
            required: List<String> = params.map { it.first }
        ): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc)
            if (params.isNotEmpty()) {
                val props = JSONObject()
                for ((n, t, d) in params) props.put(n, JSONObject().put("type", t).put("description", d))
                val req = JSONArray()
                required.forEach { req.put(it) }
                o.put(
                    "parameters",
                    JSONObject().put("type", "OBJECT").put("properties", props).put("required", req)
                )
            }
            return o
        }

        val TOOLS: JSONArray by lazy {
            val decls = JSONArray()
            decls.put(fn("open_app", "Launch an installed app by name.", listOf(p("app_name", "STRING", "App name, e.g. WhatsApp"))))
            decls.put(fn("click", "Click the element with this index.", listOf(p("index", "INTEGER", "Element index from the list"))))
            decls.put(fn("long_click", "Long-press the element with this index.", listOf(p("index", "INTEGER", "Element index"))))
            decls.put(
                fn(
                    "type_text", "Set the text of an input field (replaces existing text).",
                    listOf(p("text", "STRING", "Text to enter"), p("index", "INTEGER", "Index of the input field; omit to use the focused field")),
                    required = listOf("text")
                )
            )
            decls.put(fn("scroll", "Scroll the screen.", listOf(p("direction", "STRING", "down, up, left or right"))))
            decls.put(fn("press_key", "Press a system key.", listOf(p("key", "STRING", "back, home, recents, notifications or enter"))))
            decls.put(
                fn(
                    "tap_xy", "Tap a point on a 0-1000 grid (x left to right, y top to bottom). Use only if the element list lacks the target.",
                    listOf(p("x", "NUMBER", "0-1000"), p("y", "NUMBER", "0-1000"))
                )
            )
            decls.put(
                fn(
                    "swipe_xy", "Swipe between two points on a 0-1000 grid.",
                    listOf(p("x1", "NUMBER", "start x"), p("y1", "NUMBER", "start y"), p("x2", "NUMBER", "end x"), p("y2", "NUMBER", "end y"))
                )
            )
            decls.put(fn("call_number", "Place a phone call (confirm with the user first).", listOf(p("number", "STRING", "Phone number"))))
            decls.put(fn("wait", "Wait for the screen to load.", listOf(p("seconds", "NUMBER", "1-10"))))
            decls.put(fn("request_screenshot", "Attach a screenshot on the next step (for images, maps, games, unlabeled UI)."))
            decls.put(fn("confirm", "Ask the user a yes/no question by voice. Required before risky actions.", listOf(p("question", "STRING", "Question to ask"))))
            decls.put(fn("done", "Finish the task and tell the user the result.", listOf(p("message", "STRING", "Final message"))))
            JSONArray().put(JSONObject().put("function_declarations", decls))
        }
    }
}
