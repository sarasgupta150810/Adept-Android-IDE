package com.saras.ide // Ensure this matches your actual package name

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import io.github.rosemoe.sora.widget.CodeEditor
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.IGrammarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var btnSync: Button
    private lateinit var btnBuild: Button
    private lateinit var consoleOutput: TextView
    private lateinit var rvFileExplorer: RecyclerView
    private lateinit var rvTabs: RecyclerView
    private lateinit var editorView: CodeEditor
    
    // UI components for Explorer Toggle
    private lateinit var projectExplorerContainer: android.widget.LinearLayout
    private lateinit var btnToggleExplorer: TextView

    private val expandedFolders = mutableSetOf<String>()
    private val openTabs = mutableListOf<File>()
    private var selectedTab: File? = null
    private lateinit var tabAdapter: TabAdapter

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val crashLog = intent?.getStringExtra("CRASH_LOG") ?: return
            val pkgName = intent.getStringExtra("PACKAGE_NAME") ?: "Unknown App"

            streamLog("\n=========================================")
            streamLog("🚨 APP CRASH DETECTED: $pkgName 🚨")
            streamLog("=========================================")
            streamLog(crashLog)
            streamLog("=========================================\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        btnBuild = findViewById(R.id.btnBuild)
        consoleOutput = findViewById(R.id.consoleOutput)
        rvFileExplorer = findViewById(R.id.rvFileExplorer)
        rvTabs = findViewById(R.id.rvTabs)
        editorView = findViewById(R.id.editorView)
        btnSync = findViewById(R.id.btnSync)
        progressBar = findViewById(R.id.progressBar)
        
        // Initialize Toggle Variables
        projectExplorerContainer = findViewById(R.id.projectExplorerContainer)
        btnToggleExplorer = findViewById(R.id.btnToggleExplorer)

        // Explorer Toggle Logic
        btnToggleExplorer.setOnClickListener {
            // Adds a smooth slide animation when toggling
            android.transition.TransitionManager.beginDelayedTransition(projectExplorerContainer.parent as android.view.ViewGroup)
            if (projectExplorerContainer.visibility == android.view.View.VISIBLE) {
                projectExplorerContainer.visibility = android.view.View.GONE
            } else {
                projectExplorerContainer.visibility = android.view.View.VISIBLE
            }
        }

        configureProfessionalEditor()
        setupSymbolBar()
        setupTextMate()
        setupTabManager()
        requestStorageAccess()

        val filter = IntentFilter("com.saras.ide.TELEMETRY_CRASH")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(telemetryReceiver, filter, 2)
        } else {
            registerReceiver(telemetryReceiver, filter)
        }

        extractCoreBuildMaterials()
        extractCompilerToolchain()
        CoroutineScope(Dispatchers.IO).launch {
            installNativeJdkIfNeeded()
            installKotlinCompilerIfNeeded()
        }

        btnBuild.setOnClickListener {
            saveCurrentFile()
            startBuildPipeline()
        }

        btnSync.setOnClickListener {
            saveCurrentFile()
            syncGradleDependencies()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(telemetryReceiver) } catch (e: Exception) {}
    }

    // ==========================================
    // EXTRACTION LOGIC
    // ==========================================
    private fun configureProfessionalEditor() {
        editorView.typefaceText = android.graphics.Typeface.MONOSPACE
        editorView.isWordwrap = false
        editorView.setTextSize(16f)
        editorView.setLineSpacing(0f, 1.2f)
    }

    private fun installNativeJdkIfNeeded() {
        val jdkDir = File(filesDir, "jdk")
        if (jdkDir.exists() && File(jdkDir, "bin/javac").exists()) return
        try {
            val zipStream = ZipInputStream(assets.open("jdk.zip"))
            var entry = zipStream.nextEntry
            while (entry != null) {
                val targetFile = File(filesDir, entry.name)
                if (entry.isDirectory) targetFile.mkdirs()
                else {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { out -> zipStream.copyTo(out) }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()
            Runtime.getRuntime().exec("chmod -R 777 ${jdkDir.absolutePath}").waitFor()
        } catch (e: Exception) {}
    }

    private fun installKotlinCompilerIfNeeded() {
        val kotlincDir = File(filesDir, "kotlinc")
        val compilerJar = File(kotlincDir, "lib/kotlin-compiler.jar")
        if (compilerJar.exists()) return

        streamLog("📦 Extracting Kotlin 2.4.0 Compiler... Please wait.")
        try {
            if (!kotlincDir.exists()) kotlincDir.mkdirs()
            ZipInputStream(assets.open("kotlinc.zip")).use { zis ->
                var zipEntry = zis.nextEntry
                while (zipEntry != null) {
                    var entryName = zipEntry.name
                    if (entryName.startsWith("kotlinc/")) entryName = entryName.substring("kotlinc/".length)
                    if (entryName.isNotEmpty()) {
                        val newFile = File(kotlincDir, entryName)
                        if (zipEntry.isDirectory) newFile.mkdirs()
                        else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    zipEntry = zis.nextEntry
                }
            }
            streamLog("✅ Kotlin Compiler Installed Successfully!")
        } catch (e: Exception) {}
    }

    private fun extractCoreBuildMaterials() {
        val buildToolsDir = File(filesDir, "build-tools")
        if (!buildToolsDir.exists()) buildToolsDir.mkdirs()
        val assetsToExtract = listOf("android.jar", "debug.keystore")
        CoroutineScope(Dispatchers.IO).launch {
            for (fileName in assetsToExtract) {
                val outFile = File(buildToolsDir, fileName)
                if (!outFile.exists()) {
                    try {
                        assets.open(fileName).use { inputStream ->
                            FileOutputStream(outFile).use { outputStream -> inputStream.copyTo(outputStream) }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun extractCompilerToolchain() {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        val toolchain = listOf("d8.jar", "apksigner.jar")
        CoroutineScope(Dispatchers.IO).launch {
            for (fileName in toolchain) {
                val outFile = File(binDir, fileName)
                if (!outFile.exists()) {
                    try {
                        assets.open(fileName).use { inputStream ->
                            FileOutputStream(outFile).use { outputStream -> inputStream.copyTo(outputStream) }
                        }
                        outFile.setExecutable(true, false)
                        outFile.setReadable(true, false)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    // ==========================================
    // ☁️ THE CLOUD DEPENDENCY ENGINE
    // ==========================================
    private suspend fun resolveViaCloudAPI(library: String, logCallback: (String) -> Unit): List<String> {
        val cleanLib = library.replace("[", "").replace("]", "").replace("(", "").replace(")", "")
        val parts = cleanLib.split(":")
        if (parts.size < 3) return listOf(cleanLib)
        val encodedName = URLEncoder.encode("${parts[0]}:${parts[1]}", "UTF-8")
        val urlString = "https://api.deps.dev/v3/systems/maven/packages/$encodedName/versions/${parts[2]}:dependencies"
        val resolvedList = mutableListOf<String>()
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode == 200) {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val nodes = json.getJSONArray("nodes")
                for (i in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(i)
                    val versionKey = node.getJSONObject("versionKey")
                    resolvedList.add("${versionKey.getString("name")}:${versionKey.getString("version")}")
                }
            } else resolvedList.add(cleanLib)
        } catch (e: Exception) { resolvedList.add(cleanLib) }
        return resolvedList
    }

    private fun syncGradleDependencies() {
        val projectPath = intent.getStringExtra("PROJECT_PATH") ?: return
        val appGradleFile = File(projectPath, "app/build.gradle")
        btnSync.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!appGradleFile.exists()) return@launch
                val gradleText = appGradleFile.readText()
                val regex = Regex("""(implementation|api)\s+['"]([^'"]+)['"]""")
                val libraries = regex.findAll(gradleText).map { it.groupValues[2] }.toList()

                if (libraries.isNotEmpty()) {
                    val cacheLibsDir = File(projectPath, ".ide/caches/libs")
                    if (!cacheLibsDir.exists()) cacheLibsDir.mkdirs()
                    val titanEngine = TitanDependencyEngine(cacheLibsDir)
                    val resolvedVersions = mutableMapOf<String, String>()

                    for (lib in libraries) {
                        if (lib.startsWith("com.github.") || lib.contains("jitpack")) {
                            titanEngine.resolveAndDownload(lib, 0, resolvedVersions) { logMsg -> streamLog(logMsg) }
                        } else {
                            val resolvedFlatList = resolveViaCloudAPI(lib) { logMsg -> streamLog(logMsg) }
                            if (resolvedFlatList.size <= 1) titanEngine.resolveAndDownload(lib, 0, resolvedVersions) { logMsg -> streamLog(logMsg) }
                            else for (flatLib in resolvedFlatList) titanEngine.resolveAndDownload(flatLib, 8, resolvedVersions) { logMsg -> streamLog(logMsg) }
                        }
                    }
                }
                streamLog("✅ Library Sync Complete.")
            } catch (e: Exception) {
                streamLog("❌ SYNC FAILED: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { btnSync.isEnabled = true; progressBar.visibility = android.view.View.GONE }
            }
        }
    }

    // ==========================================
    // 💥 THE HYBRID BUILD ENGINE
    // ==========================================
    private fun startBuildPipeline() {
        val projectPath = intent.getStringExtra("PROJECT_PATH") ?: return

        btnBuild.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        consoleOutput.text = "Initializing Hybrid Build Engine...\n"

        CoroutineScope(Dispatchers.IO).launch {
            val startTime = System.currentTimeMillis()
            try {
                val appDir = File(projectPath, "app")
                val srcDir = File(appDir, "src/main")
                val resDir = File(srcDir, "res")
                val originalManifestFile = File(srcDir, "AndroidManifest.xml")

                val buildDir = File(appDir, "build")
                val genDir = File(buildDir, "gen")
                val objDir = File(buildDir, "obj")

                if (buildDir.exists()) buildDir.deleteRecursively()
                buildDir.mkdirs()
                genDir.mkdirs()
                objDir.mkdirs()

                val binDir = File(filesDir, "bin")
                val aapt2 = File(applicationInfo.nativeLibraryDir, "libaapt2.so").absolutePath
                val androidJar = File(filesDir, "build-tools/android.jar").absolutePath

                // ⚙️ STAGE 0: GRADLE PARSER & MANIFEST SYNCER
                streamLog("\n🔍 Parsing build.gradle...")
                val targetGradleFile = File(appDir, "build.gradle")

                // 💥 Hooking into your advanced GradleConfig engine!
                val gradleConfig = GradleParser.parse(targetGradleFile)
                streamLog("  └─ App ID: ${gradleConfig.applicationId}")
                streamLog("  └─ Target SDK: ${gradleConfig.targetSdk}")

                streamLog("\n🔄 Syncing AndroidManifest.xml...")
                val manifestFile = ManifestSyncer.sync(originalManifestFile, buildDir, gradleConfig)

                // 📚 STAGE 0.5: GATHER DEPENDENCIES
                streamLog("\n🔍 Gathering Dependencies...")
                val cacheLibsDir = File(projectPath, ".ide/caches/libs")
                val libraryJars = mutableListOf<String>()
                val libraryResDirs = mutableListOf<File>()
                val libraryPackages = mutableSetOf<String>()

                if (cacheLibsDir.exists()) {
                    cacheLibsDir.listFiles()?.forEach { file ->
                        if (file.isDirectory && file.name.endsWith("-extracted")) {
                            val libRes = File(file, "res")
                            if (libRes.exists()) libraryResDirs.add(libRes)
                            val classesJar = File(file, "classes.jar")
                            if (classesJar.exists()) libraryJars.add(classesJar.absolutePath)

                            val libManifest = File(file, "AndroidManifest.xml")
                            if (libManifest.exists()) {
                                val match = Regex("""package\s*=\s*['"]([^'"]+)['"]""").find(libManifest.readText())
                                if (match != null) libraryPackages.add(match.groupValues[1])
                            }
                        } else if (file.isFile && file.extension == "jar") {
                            libraryJars.add(file.absolutePath)
                        }
                    }
                }

                val hasModernKotlin = libraryJars.any { it.contains("kotlin-stdlib-1.8") || it.contains("kotlin-stdlib-1.9") || it.contains("kotlin-stdlib-2.") }
                if (hasModernKotlin) {
                    libraryJars.removeAll { it.contains("kotlin-stdlib-jdk7") || it.contains("kotlin-stdlib-jdk8") }
                }

                val cpBuilder = java.lang.StringBuilder(androidJar)
                for (lib in libraryJars) cpBuilder.append(":").append(lib)
                val finalClasspath = cpBuilder.toString()

                // 🎨 STAGE 1: AAPT2 NATIVE MULTI-LINKING
                streamLog("\n🎨 Compiling Resources (AAPT2)...")

                val resZipFile = File(objDir, "compiled_res.zip")
                if (resDir.exists()) {
                    if (!runCommand(listOf(aapt2, "compile", "--dir", resDir.absolutePath, "-o", resZipFile.absolutePath), appDir, "AAPT2 Compile Project")) throw Exception("Project resource compilation failed.")
                }

                val compiledLibZips = mutableListOf<String>()
                var libCount = 0
                for (libRes in libraryResDirs) {
                    val libZip = File(objDir, "lib_res_${libCount++}.zip")
                    if (!libZip.exists()) {
                        runCommand(listOf(aapt2, "compile", "--dir", libRes.absolutePath, "-o", libZip.absolutePath), appDir, "AAPT2 Compile Lib $libCount")
                    }
                    if (libZip.exists()) compiledLibZips.add(libZip.absolutePath)
                }

                streamLog("  └─ Linking Resources...")
                val baseApkFile = File(buildDir, "base.apk")
                val linkCmd = mutableListOf(
                    aapt2, "link",
                    "-I", androidJar,
                    "--manifest", manifestFile.absolutePath, // 🛡️ Using Synced Manifest!
                    "--java", genDir.absolutePath,
                    "-o", baseApkFile.absolutePath,
                    "--auto-add-overlay"
                )

                if (libraryPackages.isNotEmpty()) {
                    linkCmd.add("--extra-packages")
                    linkCmd.add(libraryPackages.joinToString(":"))
                }

                if (resZipFile.exists()) {
                    linkCmd.add("-R")
                    linkCmd.add(resZipFile.absolutePath)
                }

                for (zipPath in compiledLibZips) {
                    linkCmd.add("-R")
                    linkCmd.add(zipPath)
                }

                if (!runCommand(linkCmd, appDir, "AAPT2 Link")) throw Exception("Resource linking failed.")
                streamLog("🎉 SUCCESS: Stage 1 complete! Generated R.java and base.apk.")

                // ⚡ STAGE 1.5: NATIVE VIEW BINDING
                if (gradleConfig.buildFeatures.viewBinding) {
                    streamLog("\n⚡ Generating ViewBinding Classes...")
                    ViewBindingGenerator.generate(resDir, genDir, gradleConfig.applicationId)
                    streamLog("  └─ Binding classes dynamically created!")
                }

                // ==========================================
                // STAGE 2: KOTLIN & JAVA DUAL-COMPILER
                // ==========================================
                streamLog("\n☕ Stage 2: Compiling Source Code...")
                val javacBin = File(filesDir, "jdk/bin/javac").absolutePath
                val javaExec = File(filesDir, "jdk/bin/java").absolutePath

                val ktFiles = mutableListOf<String>()
                val javaFiles = mutableListOf<String>()

                File(srcDir, "java").walkTopDown().forEach {
                    if (it.isFile && it.extension == "kt") ktFiles.add(it.absolutePath)
                    if (it.isFile && it.extension == "java") javaFiles.add(it.absolutePath)
                }
                genDir.walkTopDown().forEach { if (it.isFile && it.extension == "java") javaFiles.add(it.absolutePath) }

                if (ktFiles.isEmpty() && javaFiles.isEmpty()) throw Exception("No source files found.")

                // 🚀 2A: KOTLIN COMPILATION
                if (ktFiles.isNotEmpty()) {
                    streamLog("  └─ Running Native Kotlin Compiler...")
                    val kotlinCompilerJar = File(filesDir, "kotlinc/lib/kotlin-compiler.jar").absolutePath
                    val safeTmpDir = File(cacheDir, "kotlin_tmp")
                    if (!safeTmpDir.exists()) safeTmpDir.mkdirs()

                    val kotlincCmd = mutableListOf("sh", "-c")
                    val ktShell = java.lang.StringBuilder()

                    ktShell.append("'").append(javaExec).append("' -Djansi.disable=true -Djava.io.tmpdir='").append(safeTmpDir.absolutePath).append("' -cp '").append(kotlinCompilerJar).append("' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -d '").append(objDir.absolutePath).append("' -classpath '").append(finalClasspath).append("' ")

                    for (file in ktFiles) ktShell.append("'").append(file).append("' ")
                    // 🛡️ We feed Java files to Kotlin so it can resolve cross-references!
                    for (file in javaFiles) ktShell.append("'").append(file).append("' ")

                    kotlincCmd.add(ktShell.toString())

                    if (!runCommand(kotlincCmd, appDir, "Kotlin Compile")) throw Exception("Kotlin Compilation failed.")
                }

                // ☕ 2B: JAVA COMPILATION
                if (javaFiles.isNotEmpty()) {
                    streamLog("  └─ Running Native Javac Compile...")

                    // 🛡️ If Kotlin ran, Javac needs to see the compiled .class files!
                    val javaClasspath = if (ktFiles.isNotEmpty()) "$finalClasspath:${objDir.absolutePath}" else finalClasspath

                    val javacCmd = mutableListOf("sh", "-c")
                    val shellString = java.lang.StringBuilder()
                    shellString.append("'").append(javacBin).append("' -d '").append(objDir.absolutePath).append("' -classpath '").append(javaClasspath).append("' ")
                    for (file in javaFiles) shellString.append("'").append(file).append("' ")
                    javacCmd.add(shellString.toString())

                    if (!runCommand(javacCmd, appDir, "Javac Compile")) throw Exception("Java Compilation failed.")
                }
                streamLog("✅ SUCCESS: Stage 2 complete.")

                // 🏃 STAGE 3: FAST D8 (INCREMENTAL DEXING)
                streamLog("\n🏃 Running D8 Dexer (Fast Mode)...")

                val dexDir = File(buildDir, "dex")
                if (!dexDir.exists()) dexDir.mkdirs()

                val libDexDir = File(projectPath, ".ide/caches/lib_dexes")
                if (!libDexDir.exists()) libDexDir.mkdirs()

                val classFiles = mutableListOf<String>()
                objDir.walkTopDown().forEach { if (it.isFile && it.extension == "class") classFiles.add(it.absolutePath) }

                val d8Jar = File(filesDir, "bin/d8.jar").absolutePath

                // 🛡️ THE DYNAMIC SDK ENGINE: Respect the user's Gradle config!
                val safeMinApi = gradleConfig.minSdk

                val libHashFile = File(libDexDir, "hash.txt")

                // 💥 SMARTER CACHE: We now include safeMinApi in the hash!
                // If the user changes their minSdk in build.gradle, the hash changes, forcing a fresh D8 rebuild.
                val currentLibHash = (libraryJars.sorted().joinToString(",") + safeMinApi.toString()).hashCode().toString()

                if (libraryJars.isNotEmpty() && (!libHashFile.exists() || libHashFile.readText() != currentLibHash)) {
                    streamLog("  └─ Config changes detected. Pre-Dexing Libraries for API $safeMinApi...")
                    libDexDir.listFiles()?.forEach { it.delete() }

                    val libD8Cmd = mutableListOf("sh", "-c")
                    val libD8Shell = java.lang.StringBuilder()
                    libD8Shell.append("'").append(javaExec).append("' -cp '").append(d8Jar).append("' com.android.tools.r8.D8 ")
                    libD8Shell.append("--min-api $safeMinApi --output '").append(libDexDir.absolutePath).append("' --lib '").append(androidJar).append("' ")
                    for (lib in libraryJars) libD8Shell.append("'").append(lib).append("' ")
                    libD8Cmd.add(libD8Shell.toString())

                    if (!runCommand(libD8Cmd, appDir, "Library Dexing")) throw Exception("Library Dexing Failed.")
                    libHashFile.writeText(currentLibHash)
                } else if (libraryJars.isNotEmpty()) {
                    streamLog("  └─ Libraries unchanged. Using cached DEX for API $safeMinApi. ⚡")
                }

                streamLog("  └─ Dexing Project Classes...")
                val projD8Cmd = mutableListOf("sh", "-c")
                val projD8Shell = java.lang.StringBuilder()
                projD8Shell.append("'").append(javaExec).append("' -cp '").append(d8Jar).append("' com.android.tools.r8.D8 ")
                projD8Shell.append("--min-api $safeMinApi --output '").append(dexDir.absolutePath).append("' --lib '").append(androidJar).append("' ")

                for (lib in libraryJars) projD8Shell.append("--classpath '").append(lib).append("' ")
                for (file in classFiles) projD8Shell.append("'").append(file).append("' ")
                projD8Cmd.add(projD8Shell.toString())

                if (!runCommand(projD8Cmd, appDir, "Project Dexing")) throw Exception("Project Dexing failed.")

                // 📦 STAGE 4: PACKAGING
                streamLog("\n📦 Running Stage 4: Packaging APK...")
                if (!injectDexIntoApk(dexDir, libDexDir, baseApkFile)) throw Exception("Packaging failed.")

                // ✍️ STAGE 5: SIGNING
                streamLog("\n✍️ Running Stage 5: Signing APK...")
                val finalApkFile = File(buildDir, "app-debug.apk")
                val apkSignerJar = File(filesDir, "bin/apksigner.jar").absolutePath
                val keystoreFile = File(filesDir, "build-tools/debug.keystore").absolutePath

                val signerCmd = mutableListOf("sh", "-c")
                val signerShell = java.lang.StringBuilder()
                signerShell.append("'").append(javaExec).append("' -jar '").append(apkSignerJar).append("' sign ")
                signerShell.append("--ks '").append(keystoreFile).append("' --ks-pass pass:android ")
                signerShell.append("--out '").append(finalApkFile.absolutePath).append("' '").append(baseApkFile.absolutePath).append("'")
                signerCmd.add(signerShell.toString())

                if (!runCommand(signerCmd, appDir, "APK Signer")) throw Exception("APK Signing failed.")

                installFinalApk(finalApkFile)

                val timeTaken = (System.currentTimeMillis() - startTime) / 1000
                streamLog("\n⏱️ Total Time: ${timeTaken}s")

            } catch (e: Exception) {
                streamLog("\n❌ BUILD PIPELINE HALTED: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    btnBuild.isEnabled = true
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }
    private suspend fun runCommand(command: List<String>, workingDir: File, stepName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                streamLog("\n🏃 Running $stepName...")
                val processBuilder = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true)
                val env = processBuilder.environment()
                env["PATH"] = "${File(filesDir, "bin").absolutePath}:${env["PATH"]}"
                val process = processBuilder.start()

                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    streamLog("  $line")

                    // 🧠 THE SMART ERROR ASSISTANT
                    val logLine = line ?: ""

                    if (logLine.contains("Redeclaration:")) {
                        streamLog("\n  💡 HINT: 'Redeclaration' means you have the exact same class or variable created twice in your project (maybe in two different files). Find the duplicate and delete it!\n")
                    }
                    else if (logLine.contains("cannot find symbol") || logLine.contains("Unresolved reference")) {
                        streamLog("\n  💡 HINT: The compiler can't find this item. Did you forget an import, misspell the name, or forget to save the file before building?\n")
                    }
                    else if (logLine.contains("BootstrapMethodError") || logLine.contains("Invoke-customs are only supported starting with")) {
                        streamLog("\n  💡 HINT: This is a Java 8 Lambda error. Ensure your D8 Dexer is set to target '--min-api 26' to natively support modern Lambdas.\n")
                    }
                }
                reader.close()

                if (process.waitFor() == 0) {
                    streamLog("✅ $stepName Completed Successfully.")
                    return@withContext true
                }
                return@withContext false
            } catch (e: Exception) { return@withContext false }
        }
    }

    // 💥 STAGE 4: Deflation Hack & Phantom DEX Filter
    private fun injectDexIntoApk(projectDexDir: File, libDexDir: File, apkFile: File): Boolean {
        return try {
            val tempApk = File(apkFile.absolutePath + ".temp")
            val zipFile = java.util.zip.ZipFile(apkFile)
            val entries = zipFile.entries().toList()

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(tempApk)).use { zos ->
                zos.setLevel(java.util.zip.Deflater.BEST_SPEED)

                val arscEntry = entries.find { it.name == "resources.arsc" }
                if (arscEntry != null) {
                    val newArsc = java.util.zip.ZipEntry("resources.arsc")
                    newArsc.method = java.util.zip.ZipEntry.STORED
                    newArsc.size = arscEntry.size
                    newArsc.compressedSize = arscEntry.size
                    newArsc.crc = arscEntry.crc
                    newArsc.extra = null

                    zos.putNextEntry(newArsc)
                    zipFile.getInputStream(arscEntry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                for (entry in entries) {
                    if (entry.name == "resources.arsc") continue
                    if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) continue

                    val newEntry = java.util.zip.ZipEntry(entry.name)
                    newEntry.method = java.util.zip.ZipEntry.DEFLATED
                    zos.putNextEntry(newEntry)
                    zipFile.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                var dexIndex = 1
                val addDexFile = { dexFile: File ->
                    val entryName = if (dexIndex == 1) "classes.dex" else "classes$dexIndex.dex"
                    val dexEntry = java.util.zip.ZipEntry(entryName)
                    dexEntry.method = java.util.zip.ZipEntry.DEFLATED
                    zos.putNextEntry(dexEntry)
                    java.io.FileInputStream(dexFile).use { it.copyTo(zos) }
                    zos.closeEntry()
                    dexIndex++
                }

                projectDexDir.listFiles { _, name -> name.endsWith(".dex") }?.forEach { addDexFile(it) }
                if (libDexDir.exists()) {
                    libDexDir.listFiles { _, name -> name.endsWith(".dex") }?.forEach { addDexFile(it) }
                }
            }
            zipFile.close()
            tempApk.renameTo(apkFile)
            true
        } catch (e: Exception) { false }
    }

    private fun streamLog(message: String) {
        runOnUiThread {
            consoleOutput.append(message + "\n")
            val scrollView = consoleOutput.parent as? android.widget.ScrollView
            scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    // --- TAB MANAGER & EXPLORER LOGIC ---
    private fun setupTabManager() {
        rvTabs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tabAdapter = TabAdapter(tabs = openTabs, selectedFile = selectedTab, onTabClicked = { selectTab(it) }, onTabClosed = { closeTab(it) })
        rvTabs.adapter = tabAdapter
    }

    private fun setupSymbolBar() {
        val symbolLayout = findViewById<android.widget.LinearLayout>(R.id.symbolLayout)
        val keys = listOf("↶", "↷", "TAB", "{", "}", "(", ")", "[", "]", ";", ":", "<", ">", "=", "/", "\"", "←", "↑", "↓", "→")
        for (key in keys) {
            val btn = android.widget.TextView(this).apply {
                text = key
                setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                textSize = 18f
                setPadding(36, 0, 36, 0)
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
            }
            btn.setOnClickListener {
                when (key) {
                    "↶" -> if (editorView.canUndo()) editorView.undo()
                    "↷" -> if (editorView.canRedo()) editorView.redo()
                    "TAB" -> editorView.commitText("    ")
                    "←" -> editorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
                    "→" -> editorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                    "↑" -> editorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
                    "↓" -> editorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                    else -> editorView.commitText(key)
                }
            }
            symbolLayout.addView(btn)
        }
    }

    private fun selectTab(file: File) {
        if (selectedTab != null) saveCurrentFile()
        selectedTab = file
        tabAdapter.selectedFile = file
        tabAdapter.notifyDataSetChanged()
        try {
            editorView.setText(file.readText())
            applySyntaxHighlighting(file.name)
        } catch (e: Exception) {}
    }

    private fun closeTab(file: File) {
        val index = openTabs.indexOf(file)
        if (index == -1) return
        if (selectedTab == file) saveCurrentFile()
        openTabs.removeAt(index)
        if (selectedTab == file) {
            if (openTabs.isNotEmpty()) selectTab(openTabs[if (index > 0) index - 1 else 0])
            else { selectedTab = null; editorView.setText("") }
        }
        tabAdapter.selectedFile = selectedTab
        tabAdapter.notifyDataSetChanged()
    }

    private fun saveCurrentFile() {
        selectedTab?.let { file ->
            try {
                if (file.exists()) FileOutputStream(file).use { it.write(editorView.text.toString().toByteArray()) }
            } catch (e: Exception) {}
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${applicationContext.packageName}")
                    startActivity(intent)
                } catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else readDeviceFiles()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            } else readDeviceFiles()
        }
    }

    private fun readDeviceFiles() {
        val projectPath = intent.getStringExtra("PROJECT_PATH") ?: return
        val rootDir = File(projectPath)
        val fileList = mutableListOf<FileItem>()
        if (rootDir.exists()) {
            fileList.add(FileItem(rootDir.name, true, 0, rootDir))
            expandedFolders.add(rootDir.absolutePath)
            scanDirectory(rootDir, 1, fileList)
        }
        rvFileExplorer.layoutManager = LinearLayoutManager(this)

        // 🚨 Connects the adapter's view and file to the new PopupMenu!
        rvFileExplorer.adapter = FileAdapter(fileList, { openTab(it) }, { toggleFolder(it) }, { view, file -> showFileContextMenu(view, file) })
    }

    // 🗂️ THE PROFESSIONAL FILE MANAGEMENT ENGINE
    private fun showFileContextMenu(anchor: android.view.View, targetFile: File) {

        // 🎨 THE FIX: Force the menu into Dark Mode to match the IDE!
        val darkContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat)
        val popup = android.widget.PopupMenu(darkContext, anchor)

        if (targetFile.isDirectory) {
            popup.menu.add(0, 1, 0, "📄 New File")
            popup.menu.add(0, 2, 0, "📁 New Folder")
        }
        popup.menu.add(0, 3, 0, "✏️ Rename")
        popup.menu.add(0, 4, 0, "🗑️ Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptForName("New File", "filename.kt") { name ->
                    val newFile = File(targetFile, name)
                    if (newFile.createNewFile()) readDeviceFiles()
                }
                2 -> promptForName("New Folder", "NewFolder") { name ->
                    val newDir = File(targetFile, name)
                    if (newDir.mkdirs()) {
                        expandedFolders.add(targetFile.absolutePath)
                        readDeviceFiles()
                    }
                }
                3 -> promptForName("Rename", targetFile.name) { newName ->
                    val newFile = File(targetFile.parentFile, newName)
                    if (targetFile.renameTo(newFile)) {
                        if (openTabs.contains(targetFile)) closeTab(targetFile)
                        readDeviceFiles()
                    }
                }
                4 -> {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Delete?")
                        .setMessage("Are you sure you want to delete ${targetFile.name}?")
                        .setPositiveButton("Yes") { _, _ ->
                            if (targetFile.deleteRecursively()) {
                                if (openTabs.contains(targetFile)) closeTab(targetFile)
                                readDeviceFiles()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun promptForName(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = android.widget.EditText(this)
        input.setText(if (title == "Rename") hint else "")
        input.hint = hint
        input.setPadding(40, 40, 40, 40)

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openTab(file: File) {
        if (!openTabs.contains(file)) openTabs.add(file)
        selectTab(file)
    }

    private fun toggleFolder(folder: File) {
        val path = folder.absolutePath
        if (expandedFolders.contains(path)) expandedFolders.remove(path) else expandedFolders.add(path)
        readDeviceFiles()
    }

    private fun showCreateFileDialog(targetFolder: File) {}

    private fun scanDirectory(dir: File, depth: Int, list: MutableList<FileItem>) {
        if (!expandedFolders.contains(dir.absolutePath)) return
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
        for (file in files) {
            if (file.name == "build" || file.name == ".gradle" || file.name == ".idea" || file.name == ".git") continue
            list.add(FileItem(file.name, file.isDirectory, depth, file))
            if (file.isDirectory) scanDirectory(file, depth + 1, list)
        }
    }

    private fun setupTextMate() {
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

            val themeRegistry = ThemeRegistry.getInstance()
            val themePath = "textmate/darcula.json"
            themeRegistry.loadTheme(IThemeSource.fromInputStream(assets.open(themePath), themePath, null), true)

            // 🗺️ THE FIX: Using the clean JSON map that we know compiles perfectly!
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            editorView.colorScheme = TextMateColorScheme.create(themeRegistry)

        } catch (e: Exception) {
            streamLog("❌ TEXTMATE ERROR: ${e.message}")
        }
    }

    private fun applySyntaxHighlighting(fileName: String) {
        try {
            val scopeName = when {
                fileName.endsWith(".kt") -> "source.kotlin"
                fileName.endsWith(".java") -> "source.java"
                fileName.endsWith(".gradle") -> "source.groovy"
                fileName.endsWith(".xml") -> "text.xml"
                else -> null
            }

            if (scopeName != null) {
                // 💥 THE FIX: Set to 'false' so it stops looking for missing bracket config files!
                val tmLanguage = io.github.rosemoe.sora.langs.textmate.TextMateLanguage.create(scopeName, false)
                editorView.setEditorLanguage(tmLanguage)

                val themeRegistry = io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()
                editorView.colorScheme = io.github.rosemoe.sora.langs.textmate.TextMateColorScheme.create(themeRegistry)

            } else {
                editorView.setEditorLanguage(null)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            editorView.setEditorLanguage(null)
        }
    }

    private fun installFinalApk(apkFile: File) {
        streamLog("\n🚀 Launching Installer...")
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", apkFile)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            startActivity(intent)
        } catch (e: Exception) {
            streamLog("❌ ERROR: Could not launch installer: ${e.message}")
        }
    }
}
