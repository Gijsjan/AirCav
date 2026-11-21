package dev.ruimdetijd.aircav

/**
 * Parses typed and (transcribed) spoken input into a command.
 *
 * Grammar (taskwarrior-CLI-shaped):
 *   task|taak add <description>     -> Add (with +tags / project:x inside the text)
 *   task|taak <n> <verb>             -> Act on task n (verb: delete/done/...)
 *   anything unprefixed              -> Add (never a command; no exceptions)
 */
sealed interface Command {
    data class Add(val description: String, val tags: List<String>, val project: String) : Command
    data class Act(val number: Int, val action: String) : Command
}

private val taskAdd = Regex("^(?:task|taak)\\s+add\\s+(.+)$", RegexOption.IGNORE_CASE)
private val taskActNumFirst =
    Regex("^(?:task|taak)\\s+(\\d+)[.,;:?]?\\s*(delete|done|complete|verwijder|verwijderen|remove)\\b", RegexOption.IGNORE_CASE)

private fun actionOf(verb: String): String =
    if (verb.equals("delete", true) || verb.equals("remove", true) ||
        verb.equals("verwijder", true) || verb.equals("verwijderen", true)
    ) "delete" else "done"

/** Parse taskwarrior-style CLI input; plain text becomes an Add. */
fun parseInput(raw: String): Command.Add? {
    val text = raw.trim()
    taskAdd.find(text)?.let { m ->
        return parseCommand(m.groupValues[1])
    }
    // handled by caller (Command.Act) via parseAct
    parseAct(text)?.let { return null }
    return parseCommand(text)
}

/** Parse `task <n> <verb>`; null when the input is not a numbered action. */
fun parseAct(raw: String): Command.Act? =
    taskActNumFirst.find(raw.trim())?.let { m ->
        Command.Act(m.groupValues[1].toInt(), actionOf(m.groupValues[2]))
    }

/** Unit-testable full parse: command or plain add. */
fun parse(input: String): Command? {
    val act = parseAct(input)
    if (act != null) return act
    return parseInput(input)
}

/**
 * Taskwarrior-style project hierarchy match: `home` matches `home`,
 * `home.klussen`, `home.klussen.garage`... (case-insensitive).
 */
fun matchesProject(taskProject: String?, contextProject: String): Boolean {
    if (taskProject == null) return false
    val tp = taskProject.lowercase()
    val cp = contextProject.lowercase()
    return tp == cp || tp.startsWith("$cp.")
}

// normalize spoken project names: lowercase, strip trailing sentence punctuation
private fun normalizeProject(value: String): String =
    value.trim().trim('.', ',', '!', '?', ';', ':').lowercase()

/** Extract description/tags/project from plain text ("buy milk +home project:work"). */
fun parseCommand(text: String): Command.Add? {
    val words = text.trim().split(Regex("\\s+")).toMutableList()
    var project = ""

    // "project:foo" as a single token
    words.removeAll { word ->
        val m = Regex("^project:(.+)$").find(word)
        if (m != null) {
            project = m.groupValues[1]
            true
        } else {
            false
        }
    }

    // "project foo" as two tokens
    val wordsIter = words.toList()
    val keep = mutableListOf<String>()
    var skip = false
    for ((idx, w) in wordsIter.withIndex()) {
        if (skip) {
            skip = false
            continue
        }
        if ((w.equals("project", true) || w.equals("project:", true)) && idx + 1 < wordsIter.size) {
            val next = wordsIter[idx + 1]
            if (project.isEmpty()) project = normalizeProject(next)
            skip = true
            continue
        }
        keep += w
    }
    words.clear()
    words.addAll(keep)
    if (project.isNotEmpty()) project = normalizeProject(project)

    val tags = mutableListOf<String>()
    val descWords = mutableListOf<String>()
    for (word in words) {
        when {
            word.startsWith("+") && word.length > 1 -> tags += word.drop(1)
            word.startsWith("@") && word.length > 1 -> tags += word.drop(1)
            else -> descWords += word
        }
    }
    val description = descWords.joinToString(" ").trim().trim('.', '!', '?', ',', ';', ':')
    if (description.isEmpty()) return null
    return Command.Add(description, tags, project)
}
