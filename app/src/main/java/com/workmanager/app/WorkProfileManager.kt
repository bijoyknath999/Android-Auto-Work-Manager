package com.workmanager.app

/**
 * Manages work profiles via ADB commands.
 */
class WorkProfileManager(private val adb: AdbConnection) {

    data class UserProfile(
        val id: Int,
        val name: String,
        val isRunning: Boolean
    )

    /**
     * Parse user list from `pm list users` output.
     * Example: UserInfo{10:Work Profile:1070} running
     */
    fun parseUsers(output: String): List<UserProfile> {
        val users = mutableListOf<UserProfile>()
        val regex = Regex("""UserInfo\{(\d+):([^:}]+)(?::[^}]*)?\}(\s*running)?""")

        for (line in output.lines()) {
            val match = regex.find(line.trim()) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val name = match.groupValues[2].trim()
            val running = match.groupValues[3].isNotEmpty()
            users.add(UserProfile(id, name, running))
        }
        return users
    }

    fun getUsers(): String {
        val out = adb.exec("pm list users")
        if (out.isBlank() || !out.contains("UserInfo")) {
            AppLogger.log("PROFILE", "pm list users unexpected output: ${out.take(120)} — retrying")
            val retry = adb.exec("pm list users")
            AppLogger.log("PROFILE", "retry output: ${retry.take(120)}")
            return retry
        }
        return out
    }

    private fun startUser(id: Int) {
        val out = adb.exec("am start-user $id")
        AppLogger.log("PROFILE", "am start-user $id -> ${out.trim().take(120)}")
    }

    data class CreateResult(val userId: Int?, val provisioned: Boolean, val log: String)

    /**
     * Creates a managed profile AND provisions it as a real work profile:
     * install our app into it, make it profile owner, then enable the profile so
     * its apps show in the launcher (with work badges). Falls back to a basic
     * managed profile if `dpm set-profile-owner` is blocked by the ROM.
     */
    fun createAndProvision(name: String): CreateResult {
        val createOut = adb.exec("pm create-user --profileOf 0 --managed \"$name\"")
        val id = Regex("id (\\d+)").find(createOut)?.groupValues?.get(1)?.toIntOrNull()
            ?: return CreateResult(null, false, "create failed: $createOut")

        val pkg = AdbAutoConnect.PACKAGE
        val sb = StringBuilder("Created profile id=$id\n")

        startUser(id)
        // The admin component must exist inside the profile to own it.
        sb.append("install self: ${adb.exec("pm install-existing --user $id $pkg").trim()}\n")

        val po = adb.exec("dpm set-profile-owner --user $id $pkg/.WorkAdminReceiver")
        sb.append("set-profile-owner: ${po.trim()}\n")
        val provisioned = po.contains("Success")

        if (provisioned) {
            // Enable the profile from inside it (onEnabled also tries this).
            adb.exec("am start --user $id -n $pkg/.ProfileBootstrapActivity")
            // Hide our own launcher icon from the work profile (app stays installed
            // for profile-owner duties, but the user doesn't see it).
            adb.exec("pm disable --user $id $pkg/.MainActivity")
            sb.append("provisioned — apps will appear in the launcher\n")
        } else {
            sb.append("not provisioned — ROM blocked dpm; use 'Open app in profile'\n")
        }
        return CreateResult(id, provisioned, sb.toString().trim())
    }

    /** Packages in [userId] that have a launcher icon (incl. system apps like
     *  Play Store), so "Open app in profile" can show everything launchable. */
    fun listLaunchableApps(userId: Int): List<String> {
        val out = adb.exec(
            "cmd package query-activities --brief --user $userId " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        // Pull the package name out of each "package/activity" component.
        val re = Regex("""([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+""")
        return re.findAll(out).map { it.groupValues[1] }
            .filter { it.contains(".") }
            .distinct().sorted().toList()
    }

    /** Enables a curated set of apps into [userId].
     *  Uses pm install-existing — skips silently if a package isn't on the device.
     *  Work Manager is intentionally excluded from this list (it is installed
     *  separately as profile owner during provisioning). */
    fun installGoogleServices(userId: Int, packages: List<String>): String {
        startUser(userId)
        val results = packages.map { p ->
            val out = adb.exec("pm install-existing --user $userId $p")
            val status = out.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { out.trim() }
            "$p: $status"
        }
        AppLogger.log("INSTALL", "installGoogleServices user=$userId\n${results.joinToString("\n")}")
        return results.joinToString("\n")
    }

    // Scripted Google sign-in tap coordinates (device-specific; adjust per device).
    private val signInXy = 534 to 1614      // "Sign in" button
    private val emailFieldXy = 173 to 503   // email input field
    private val nextXy = 832 to 1919        // "Next" button

    /** Opens the Google "Add account" flow inside [userId] and auto-types
     *  [email], then presses Next. Best-effort UI automation via `input` — the
     *  user still completes password / 2FA (which must not be automated). */
    fun addGoogleAccount(userId: Int, email: String): String {
        AppLogger.log("ACCOUNT", "Google sign-in automation in user $userId")
        startUser(userId)
        // Play Store runs AS the profile, so signing in there adds the account to
        // THIS profile (Settings' add-account is singleUser → always main user).
        val out = launchApp("com.android.vending", userId)
        if (out.startsWith("ERROR")) {
            return "ERROR: Play Store isn't in this profile — tap 'Add Google Play to profile' first"
        }

        Thread.sleep(2000)
        tap(signInXy.first, signInXy.second)          // Sign in
        Thread.sleep(5000)
        tap(emailFieldXy.first, emailFieldXy.second)  // work email field
        Thread.sleep(500)
        if (email.isNotBlank()) {
            adb.exec("input text \"$email\"")
            AppLogger.log("ACCOUNT", "typed email")
        }
        Thread.sleep(500)
        tap(nextXy.first, nextXy.second)              // Next
        return "ran sign-in automation"
    }

    /** Dumps the screen and logs every clickable/editable/labelled element with
     *  its center coordinate, so you can see exactly what to tap and where. */
    @Suppress("SdCardPath")
    fun dumpUiElements(): String {
        adb.exec("rm -f /sdcard/wm_ui.xml")
        adb.exec("uiautomator dump /sdcard/wm_ui.xml")
        val xml = adb.exec("cat /sdcard/wm_ui.xml")
        AppLogger.log("UI", "screen size: ${adb.exec("wm size").trim().substringAfterLast(' ')}")

        val nodeRe = Regex("<node\\b[^>]*>")
        val boundsRe = Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")
        fun attr(tag: String, name: String) = Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()

        var count = 0
        for (m in nodeRe.findAll(xml)) {
            val tag = m.value
            val clickable = tag.contains("clickable=\"true\"")
            val editable = tag.contains("android.widget.EditText")
            val label = listOf(attr(tag, "text"), attr(tag, "content-desc"), attr(tag, "resource-id"))
                .firstOrNull { it.isNotBlank() }.orEmpty()
            if (!clickable && !editable && label.isBlank()) continue
            val b = boundsRe.find(tag) ?: continue
            val (x1, y1, x2, y2) = b.destructured
            val cx = (x1.toInt() + x2.toInt()) / 2
            val cy = (y1.toInt() + y2.toInt()) / 2
            AppLogger.log("UI", "($cx,$cy) ${if (editable) "[edit] " else if (clickable) "[tap] " else ""}${label.take(40)}")
            if (++count >= 60) break
        }
        return "dumped $count elements — see Logs"
    }

    /** Taps an absolute screen coordinate. Logs the command output — `input tap`
     *  prints nothing on success; any text means it failed (e.g. a MIUI/HyperOS
     *  SecurityException because "USB debugging (Security settings)" is off). */
    fun tap(x: Int, y: Int): String {
        val out = adb.exec("input tap $x $y").trim()
        val msg = if (out.isEmpty()) "tapped ($x,$y) OK"
        else "tap ($x,$y) FAILED -> ${out.take(160)}"
        AppLogger.log("UI", msg)
        return msg
    }

    /** Launches [packageName] inside [userId]: ensure the profile is running,
     *  resolve its launcher activity, then start it for that user. */
    fun launchApp(packageName: String, userId: Int): String {
        startUser(userId)
        val resolve = adb.exec("cmd package resolve-activity --brief --user $userId $packageName")
        val component = resolve.lineSequence().map { it.trim() }
            .lastOrNull { it.contains("/") && !it.contains(" ") }
            ?: return "ERROR: no launchable activity found for $packageName"
        return adb.exec("am start --user $userId -n $component")
    }

    /** Fully removes a profile: stop it, then remove the user. Removing the user
     *  deletes its apps, so the launcher drops their (badged) home-screen icons. */
    fun removeUser(userId: Int): String {
        adb.exec("am stop-user -f $userId")
        val out = adb.exec("pm remove-user $userId")
        AppLogger.log("PROFILE", "remove-user $userId -> ${out.trim().take(120)}")
        return out
    }

    fun installExisting(packageName: String, userId: Int): String {
        val out = adb.exec("pm install-existing --user $userId $packageName")
        AppLogger.log("INSTALL", "install-existing $packageName (user $userId) -> ${out.trim().take(140)}")
        return out
    }

    /**
     * Installs an app into [userId] from a picked file, supporting both:
     *  • plain **.apk** — single `pm install`, and
     *  • bundles **.xapk / .apks / .apkm** — a ZIP of split APKs (+ optional OBB),
     *    installed as one split session via `pm install-create/write/commit`.
     *
     * [open] must return a *fresh* stream each call (the file is read more than
     * once). Returns pm's output ("Success" on success).
     */
    fun installApk(open: () -> java.io.InputStream?, userId: Int): String {
        val splitEntries = zipApkEntries(open)
        AppLogger.log("INSTALL", "detected ${splitEntries.size} inner apk entries${if (splitEntries.isNotEmpty()) " " + splitEntries.take(4) else ""}")
        return if (splitEntries.isEmpty()) installSingle(open, userId)
        else installBundle(open, userId)
    }

    private fun installSingle(open: () -> java.io.InputStream?, userId: Int): String {
        val remote = "/data/local/tmp/wm_${System.currentTimeMillis()}.apk"
        val input = open() ?: return "ERROR: cannot open file"
        val pushed = input.use { adb.push(remote, it) }
        if (pushed < 0) return "ERROR: failed to upload APK"
        val out = adb.exec("pm install -r -d --user $userId \"$remote\"")
        adb.exec("rm -f \"$remote\"")
        AppLogger.log("INSTALL", "single apk ($pushed bytes) -> ${out.take(120)}")
        return out
    }

    @Suppress("SdCardPath")
    private fun installBundle(open: () -> java.io.InputStream?, userId: Int): String {
        val ts = System.currentTimeMillis()
        val tmpFiles = mutableListOf<String>()   // pushed split apk paths
        val sizes = mutableListOf<Long>()
        val obbTemp = mutableListOf<Pair<String, String>>()  // tmpPath -> obb filename
        var manifestText = ""

        // Single pass over the zip: push splits to flat temp files (no mkdir),
        // stash any OBB temporarily, and capture manifest.json for the package id.
        try {
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(open() ?: return "ERROR: cannot open file")).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        entry.isDirectory -> {}
                        name.endsWith(".apk", true) -> {
                            val path = "/data/local/tmp/wm_${ts}_${tmpFiles.size}.apk"
                            val n = adb.push(path, NonClosingStream(zis))
                            if (n < 0) { cleanup(tmpFiles); return "ERROR: upload failed for $name" }
                            tmpFiles.add(path); sizes.add(n)
                        }
                        name.substringAfterLast('/') == "manifest.json" ->
                            manifestText = zis.readBytes().toString(Charsets.UTF_8)
                        name.endsWith(".obb", true) -> {
                            val path = "/data/local/tmp/wm_${ts}_obb_${obbTemp.size}"
                            if (adb.push(path, NonClosingStream(zis)) >= 0)
                                obbTemp.add(path to name.substringAfterLast('/'))
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            cleanup(tmpFiles)
            return "ERROR: reading bundle: ${e.message}"
        }

        if (tmpFiles.isEmpty()) return "ERROR: no APK splits found in bundle"
        AppLogger.log("INSTALL", "pushed ${tmpFiles.size} splits, sizes=$sizes")

        // Move any OBB into place once we know the package name.
        val pkg = Regex(""""package_name"\s*:\s*"([^"]+)"""").find(manifestText)?.groupValues?.get(1).orEmpty()
        var obbPushed = 0
        if (pkg.isNotEmpty() && obbTemp.isNotEmpty()) {
            adb.exec("mkdir -p /sdcard/Android/obb/$pkg")
            obbTemp.forEach { (tmp, fn) ->
                val r = adb.exec("mv \"$tmp\" \"/sdcard/Android/obb/$pkg/$fn\"")
                if (!r.contains("ERROR") && !r.contains("failed")) obbPushed++
            }
        }
        obbTemp.forEach { (tmp, _) -> adb.exec("rm -f \"$tmp\"") }  // clean leftovers

        // Split install session. Each split passed by path WITH -S size (this
        // ROM's install-write requires -S even for a path).
        val createOut = adb.exec("pm install-create -r -d --user $userId")
        AppLogger.log("INSTALL", "install-create -> ${createOut.trim().take(140)}")
        val session = Regex("""\[(\d+)]""").find(createOut)?.groupValues?.get(1)
            ?: run { cleanup(tmpFiles); return "ERROR: install-create failed: ${createOut.trim()}" }

        for (i in tmpFiles.indices) {
            val w = adb.exec("pm install-write -S ${sizes[i]} $session split$i \"${tmpFiles[i]}\"")
            AppLogger.log("INSTALL", "write split$i -> ${w.trim().take(140)}")
            if (!w.contains("Success")) {
                adb.exec("pm install-abandon $session"); cleanup(tmpFiles)
                return "ERROR: write split$i: ${w.trim()}"
            }
        }

        val commit = adb.exec("pm install-commit $session")
        AppLogger.log("INSTALL", "install-commit -> ${commit.trim().take(140)}")
        cleanup(tmpFiles)
        return if (obbPushed > 0) "$commit (OBB: $obbPushed)" else commit
    }

    private fun cleanup(files: List<String>) = files.forEach { adb.exec("rm -f \"$it\"") }

    // ── Bundle helpers ─────────────────────────────────────────────────────────

    /** Inner ".apk" entry names — non-empty only for xapk/apks/apkm bundles.
     *  A plain APK is itself a zip but contains no ".apk" entries, so this is []. */
    private fun zipApkEntries(open: () -> java.io.InputStream?): List<String> = try {
        val ins = open() ?: return emptyList()
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins)).use { zis ->
            val names = mutableListOf<String>()
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".apk", true)) names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
            names
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Wraps a ZipInputStream so push()'s copy doesn't close the whole zip when
     *  it finishes reading one entry. */
    private class NonClosingStream(private val s: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int = s.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
        override fun close() { /* leave the underlying zip open */ }
    }
}
