package com.saras.ide

import java.io.File

object ViewBindingGenerator {
fun generate(resDir: File, genDir: File, packageName: String) {
val layoutDir = File(resDir, "layout")
if (!layoutDir.exists()) return

// Safely create the databinding package directory
val bindingPackageDir = File(genDir, packageName.replace('.', '/') + "/databinding")
bindingPackageDir.mkdirs()

layoutDir.listFiles { _, name -> name.endsWith(".xml") }?.forEach { xmlFile ->
val layoutName = xmlFile.nameWithoutExtension

// Convert activity_main.xml to ActivityMainBinding
val className = layoutName.split("_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Binding"

// Store Pair<Type, Pair<RawXmlId, CamelCaseVariableId>>
val ids = mutableListOf<Pair<String, Pair<String, String>>>()

val xmlContent = xmlFile.readText()

// Regex to grab both the XML Tag Name and the ID string
val idRegex = Regex("""<([a-zA-Z0-9_.]+)[^>]*?android:id\s*=\s*["']@\+id/([^"']+)["']""")

idRegex.findAll(xmlContent).forEach { match ->
var type = match.groupValues[1]

// Intelligently map standard Android views to their correct packages
if (!type.contains(".")) {
type = when (type) {
"View", "ViewGroup", "ViewStub", "SurfaceView", "TextureView" -> "android.view.$type"
"WebView" -> "android.webkit.$type"
else -> "android.widget.$type"
}
}

val rawId = match.groupValues[2]

// Advanced: Convert snake_case XML IDs to camelCase Java variables (btn_test -> btnTest)
val camelId = rawId.split("_").mapIndexed { index, s ->
if (index == 0) s else s.replaceFirstChar { c -> c.uppercase() }
}.joinToString("")

ids.add(Pair(type, Pair(rawId, camelId)))
}

val javaCode = StringBuilder()

// 📦 1. Package and Imports
javaCode.append("package $packageName.databinding;\n\n")
javaCode.append("import android.view.View;\n")
javaCode.append("import android.view.LayoutInflater;\n")
javaCode.append("import android.view.ViewGroup;\n\n")

// 🏗️ 2. Class Declaration and Fields
javaCode.append("public final class $className {\n")
javaCode.append("    private final View rootView;\n")

ids.forEach { (type, idData) ->
javaCode.append("    public final $type ${idData.second};\n")
}

// 🛠️ 3. Constructor
javaCode.append("\n    private $className(View rootView")
ids.forEach { (type, idData) -> javaCode.append(", $type ${idData.second}") }
javaCode.append(") {\n        this.rootView = rootView;\n")
ids.forEach { (_, idData) -> javaCode.append("        this.${idData.second} = ${idData.second};\n") }
javaCode.append("    }\n\n")

// 🔗 4. The Kotlin getRoot() Fix!
javaCode.append("    public View getRoot() {\n")
javaCode.append("        return rootView;\n")
javaCode.append("    }\n\n")

// 🎈 5. Standard Inflate Method
javaCode.append("    public static $className inflate(LayoutInflater inflater) {\n")
javaCode.append("        return inflate(inflater, null, false);\n")
javaCode.append("    }\n\n")

// 🎈 6. Advanced Inflate Method
javaCode.append("    public static $className inflate(LayoutInflater inflater, ViewGroup parent, boolean attachToParent) {\n")
javaCode.append("        View root = inflater.inflate($packageName.R.layout.$layoutName, parent, false);\n")
javaCode.append("        if (attachToParent) {\n")
javaCode.append("            parent.addView(root);\n")
javaCode.append("        }\n")
javaCode.append("        return bind(root);\n")
javaCode.append("    }\n\n")

// 🪢 7. Bind Method (Safe findViewById execution)
javaCode.append("    public static $className bind(View rootView) {\n")
ids.forEach { (type, idData) ->
javaCode.append("        $type ${idData.second} = rootView.findViewById($packageName.R.id.${idData.first});\n")
}

javaCode.append("\n        return new $className(rootView")
ids.forEach { (_, idData) -> javaCode.append(", ${idData.second}") }
javaCode.append(");\n    }\n")

javaCode.append("}\n")

// 💾 8. Write the dynamically generated class to disk
File(bindingPackageDir, "$className.java").writeText(javaCode.toString())
}
}
}