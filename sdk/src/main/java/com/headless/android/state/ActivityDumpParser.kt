package com.headless.android.state

/**
 * The single parser for `dumpsys activity activities` output.
 *
 * ## Why this class exists
 *
 * Display-scoped parsing of this output produced three separate false conclusions during
 * development, each of which looked like success:
 *
 *  1. A check using `line.contains("Display #")` also matched incidental references inside
 *     task/activity detail lines, so the "current section" id was reassigned mid-section
 *     and a display-0 task could be attributed to the target display — reporting a launch
 *     as verified on a hidden display when the app was elsewhere.
 *  2. A resumed-package lookup returned the wrong app (reported `com.whatsapp` while Gmail
 *     was the app under automation), because display 0 prints `ResumedActivity:` while
 *     secondary displays print `topResumedActivity=`, and the two were conflated.
 *  3. The primitive-audit harness recorded an empty resumed package in **all 105 rows**,
 *     silently, for the same reason.
 *
 * Therefore: one implementation, section-strict, with unit tests over real captured output.
 *
 * ## Format notes (from real device output, Android 14 / HyperOS)
 *
 * Section headers start at column 0:
 * ```
 * Display #14 (activities from top to bottom):
 * Display #0 (activities from top to bottom):
 * ```
 * Within a section, both of these forms appear and both mean "the resumed activity":
 * ```
 *     topResumedActivity=ActivityRecord{87df313 u0 com.pkg/.Activity t2}
 *   ResumedActivity: ActivityRecord{87df313 u0 com.pkg/.Activity t2}
 * ```
 * Tasks appear as:
 * ```
 *   * Task{98a8dac #34449 type=standard A=10193:com.android.chrome U=0 visible=true ...}
 * ```
 *
 * ActivityManager may still print a section for a display DisplayManager no longer reports
 * (observed: sections for displays 13/14 after those displays were destroyed), so the
 * presence of a section is not proof the display exists — cross-check with
 * [StateEngine.isDisplayAlive].
 */
object ActivityDumpParser {

    private val SECTION_HEADER = Regex("""^Display #(\d+)\b""")
    // NOTE: Android uses ICU regex, which — unlike the JVM engine — rejects an
    // unescaped closing brace. An unescaped '}' here compiled fine in JVM unit tests but
    // threw PatternSyntaxException on device, failing this object's initializer and
    // surfacing as NoClassDefFoundError at every call site. Escape BOTH braces.
    private val RESUMED_SECONDARY = Regex("""topResumedActivity=ActivityRecord\{[^}]*\}""")
    private val RESUMED_DEFAULT = Regex("""ResumedActivity:\s*ActivityRecord\{[^}]*\}""")
    // NOTE: the class-name part must allow '$' (inner classes appear as com.pkg/.Outer$Inner).
    // In a Kotlin raw string a bare '$' starts a template, which previously produced an
    // invalid pattern and made this object fail to initialize at runtime
    // (ExceptionInInitializerError -> NoClassDefFoundError at every call site).
    // ${'$'} emits a literal dollar inside a raw string.
    private val COMPONENT_IN_RECORD = Regex("""\b([a-zA-Z][\w.]*)/([\w.${'$'}]+)""")
    private val TASK_PACKAGE = Regex("""\bA=\d+:([\w.]+)""")

    /** One display's parsed contents. */
    data class DisplaySection(
        val displayId: Int,
        val resumedPackage: String?,
        val taskPackages: Set<String>
    )

    /**
     * Parses the full dump into per-display sections.
     *
     * Only column-0 `Display #<id>` lines start a new section; every other line is
     * attributed to the section it appears within, so incidental "Display #0" mentions
     * inside detail lines cannot move the cursor.
     */
    fun parse(dump: String): Map<Int, DisplaySection> {
        val sections = LinkedHashMap<Int, DisplaySection>()
        var currentId: Int? = null
        var resumed: String? = null
        val tasks = mutableSetOf<String>()

        fun flush() {
            val id = currentId ?: return
            sections[id] = DisplaySection(id, resumed, tasks.toSet())
        }

        for (rawLine in dump.lineSequence()) {
            val headerId = sectionHeaderId(rawLine)
            if (headerId != null) {
                flush()
                currentId = headerId
                resumed = null
                tasks.clear()
                continue
            }
            if (currentId == null) continue

            if (resumed == null) {
                resumed = resumedPackageFromLine(rawLine)
            }
            taskPackageFromLine(rawLine)?.let { tasks.add(it) }
        }
        flush()
        return sections
    }

    /** Returns the display id if [rawLine] is a genuine section header, else null. */
    fun sectionHeaderId(rawLine: String): Int? {
        // Headers are at column 0; anything indented is detail, not a header.
        if (rawLine.isEmpty() || rawLine[0].isWhitespace()) return null
        return SECTION_HEADER.find(rawLine)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts the resumed activity's package from a line, handling both the
     * `topResumedActivity=` (secondary display) and `ResumedActivity:` (default display)
     * spellings. Returns null if the line is not a resumed-activity line.
     */
    fun resumedPackageFromLine(rawLine: String): String? {
        val record = RESUMED_SECONDARY.find(rawLine)?.value
            ?: RESUMED_DEFAULT.find(rawLine)?.value
            ?: return null
        // "ActivityRecord{hash u0 com.pkg/.Activity t2}" — take the component token.
        return COMPONENT_IN_RECORD.find(record)?.groupValues?.get(1)
    }

    /** Extracts a task's package from a `Task{... A=<uid>:<package> ...}` line. */
    fun taskPackageFromLine(rawLine: String): String? {
        if (!rawLine.contains("Task{")) return null
        return TASK_PACKAGE.find(rawLine)?.groupValues?.get(1)
    }

    /** Package of the resumed activity on [displayId], or null. */
    fun resumedPackageOnDisplay(dump: String, displayId: Int): String? =
        parse(dump)[displayId]?.resumedPackage

    /**
     * Best available answer to "which app is on [displayId]" — the resumed activity's
     * package if there is one, otherwise the single task's package.
     *
     * A display can legitimately host a task with **no resumed activity**: observed on
     * this device with Chrome on a virtual display as
     * `Task{... A=10193:com.android.chrome ... mode=pinned}` and no `topResumedActivity=`
     * line at all. Reporting "no app" in that situation is misleading — the app is
     * there, it just isn't the resumed one — so callers get the task-derived package
     * with [foregroundPackageIsResumed] available to distinguish the two cases.
     */
    fun foregroundPackageOnDisplay(dump: String, displayId: Int): String? {
        val section = parse(dump)[displayId] ?: return null
        section.resumedPackage?.let { return it }
        return section.taskPackages.singleOrNull()
    }

    /** True if [foregroundPackageOnDisplay] came from a resumed activity rather than a task. */
    fun foregroundPackageIsResumed(dump: String, displayId: Int): Boolean =
        parse(dump)[displayId]?.resumedPackage != null

    /** Every display id that has a task for [packageName]. */
    fun displayIdsHosting(dump: String, packageName: String): Set<Int> =
        parse(dump).values
            .filter { section ->
                section.taskPackages.contains(packageName) || section.resumedPackage == packageName
            }
            .map { it.displayId }
            .toSet()
}
