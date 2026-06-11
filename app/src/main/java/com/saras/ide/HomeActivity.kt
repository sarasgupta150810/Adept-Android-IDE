package com.saras.ide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class HomeActivity : AppCompatActivity() {

private lateinit var rvProjects: RecyclerView
// 💥 THE SILVER BULLET PATH: Uses the app's guaranteed, unblockable storage zone.
// We use 'by lazy' so it waits for the app to fully load before grabbing the path.
private val workspacePath by lazy {
getExternalFilesDir(null)!!.absolutePath + "/AdeptProjects"
}
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_home)

val toolbar = findViewById<Toolbar>(R.id.homeToolbar)
setSupportActionBar(toolbar)
supportActionBar?.title = "Adept Native IDE"

rvProjects = findViewById(R.id.rvProjects)
rvProjects.layoutManager = LinearLayoutManager(this)

val fabNewProject = findViewById<FloatingActionButton>(R.id.fabNewProject)
fabNewProject.setOnClickListener {
// Check if we actually have the master permission before trying to create a folder!
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
Toast.makeText(this, "You must grant 'All Files Access' first!", Toast.LENGTH_LONG).show()
requestMasterStorageAccess()
} else {
showNewProjectDialog()
}
}

requestMasterStorageAccess()
}

// --- 1. DYNAMIC PROJECT SCANNER ---
private fun loadRealProjects() {
val workspaceDir = File(workspacePath)

if (!workspaceDir.exists()) {
workspaceDir.mkdirs() // Create the master folder
}

val projectFolders = workspaceDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

if (projectFolders.isEmpty()) {
Toast.makeText(this, "Workspace empty. Click + to create a project!", Toast.LENGTH_SHORT).show()
}

// Wire up both the short click (open) and long click (delete)
rvProjects.adapter = ProjectAdapter(projectFolders, workspacePath,
onProjectClicked = { projectName ->
val projectPath = File(workspaceDir, projectName).absolutePath
val intent = Intent(this, MainActivity::class.java)
intent.putExtra("PROJECT_PATH", projectPath)
startActivity(intent)
},
onProjectLongClicked = { projectName ->
showDeleteConfirmation(projectName)
}
)
}
// --- 2. UPGRADED NEW PROJECT GENERATOR ---
private fun showNewProjectDialog() {
// Create a custom layout programmatically to hold two input fields
val layout = android.widget.LinearLayout(this)
layout.orientation = android.widget.LinearLayout.VERTICAL
layout.setPadding(50, 40, 50, 10)

// Project Name Input
val nameInput = android.widget.EditText(this)
nameInput.hint = "Project Name (e.g., Sanjay App)"
nameInput.setTextColor(android.graphics.Color.WHITE)
nameInput.setHintTextColor(android.graphics.Color.GRAY)
layout.addView(nameInput)

// Package Name Input
val packageInput = android.widget.EditText(this)
packageInput.hint = "Package Name (e.g., com.saras.sanjayapp)"
packageInput.setTextColor(android.graphics.Color.WHITE)
packageInput.setHintTextColor(android.graphics.Color.GRAY)
packageInput.layoutParams = android.widget.LinearLayout.LayoutParams(
android.view.ViewGroup.LayoutParams.MATCH_PARENT,
android.view.ViewGroup.LayoutParams.WRAP_CONTENT
).apply { setMargins(0, 20, 0, 0) } // Add a little space between inputs
layout.addView(packageInput)

AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
.setTitle("Create New Project")
.setView(layout)
.setPositiveButton("Create") { _, _ ->
val projectName = nameInput.text.toString().trim()
val packageName = packageInput.text.toString().trim()

if (projectName.isNotEmpty() && packageName.isNotEmpty()) {
// Pass BOTH names to our scaffolding engine
createNewProjectFolder(projectName, packageName)
} else {
Toast.makeText(this, "Both fields are required!", Toast.LENGTH_SHORT).show()
}
}
.setNegativeButton("Cancel", null)
.show()
}

private fun createNewProjectFolder(name: String, packageName: String) {
val newProjectDir = File(workspacePath, name)

try {
if (!newProjectDir.exists()) {
val created = newProjectDir.mkdirs()
if (created) {
val packagePath = packageName.replace(".", "/")

// 1. Core Directories
val appDir = File(newProjectDir, "app")
val javaDir = File(appDir, "src/main/java/$packagePath")
val resDir = File(appDir, "src/main/res")

javaDir.mkdirs()
File(resDir, "layout").mkdirs()
File(resDir, "drawable").mkdirs()
File(resDir, "values").mkdirs()
File(resDir, "mipmap-anydpi-v26").mkdirs()

// 2. ROOT FILES: settings.gradle & gradle.properties
File(newProjectDir, "settings.gradle").writeText(
"""
pluginManagement {
repositories {
google()
mavenCentral()
gradlePluginPortal()
}
}
dependencyResolutionManagement {
repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
repositories {
google()
mavenCentral()
}
}
rootProject.name = "$name"
include ':app'
""".trimIndent()
)

File(newProjectDir, "gradle.properties").writeText(
"""
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
""".trimIndent()
)

// 3. ROOT FILES: Project-level build.gradle
File(newProjectDir, "build.gradle").writeText(
"""
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
id 'com.android.application' version '8.1.0' apply false
id 'org.jetbrains.kotlin.android' version '1.9.0' apply false
}
""".trimIndent()
)

// 4. APP FILES: App-level build.gradle
File(appDir, "build.gradle").writeText(
"""
plugins {
id 'com.android.application'
id 'org.jetbrains.kotlin.android'
}

android {
namespace '$packageName'
compileSdk 36

defaultConfig {
applicationId "$packageName"
minSdk 24
targetSdk 36
versionCode 1
versionName "1.0"
}

buildTypes {
release {
minifyEnabled false
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
}
}
compileOptions {
sourceCompatibility JavaVersion.VERSION_17
targetCompatibility JavaVersion.VERSION_17
}
kotlinOptions {
jvmTarget = '17'
}
}

dependencies {
implementation 'androidx.core:core-ktx:1.12.0'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.10.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
}
""".trimIndent()
)

// 5. APP FILES: AndroidManifest.xml
File(appDir, "src/main/AndroidManifest.xml").writeText(
"""
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="$packageName">
<application
android:allowBackup="true"
android:label="$name"
android:supportsRtl="true"
android:theme="@style/Theme.AppCompat.DayNight.DarkActionBar">
<activity android:name=".MainActivity" android:exported="true">
<intent-filter>
<action android:name="android.intent.action.MAIN" />
<category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
</activity>
</application>
</manifest>
""".trimIndent()
)

// 6. SOURCE FILES: MainActivity.kt & Layouts
File(javaDir, "MainActivity.kt").writeText(
"""
package $packageName

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)
}
}
""".trimIndent()
)

File(resDir, "layout/activity_main.xml").writeText(
"""
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
android:layout_width="match_parent"
android:layout_height="match_parent"
android:gravity="center"
android:orientation="vertical">

<TextView
android:layout_width="wrap_content"
android:layout_height="wrap_content"
android:text="Hello from $name!"
android:textSize="24sp" />

</LinearLayout>
""".trimIndent()
)

File(resDir, "values/strings.xml").writeText(
"""
<?xml version="1.0" encoding="utf-8"?>
<resources>
<string name="app_name">$name</string>
</resources>
""".trimIndent()
)

File(resDir, "values/colors.xml").writeText(
"""
<?xml version="1.0" encoding="utf-8"?>
<resources>
<color name="black">#FF000000</color>
<color name="white">#FFFFFFFF</color>
<color name="primary">#3794FF</color>
</resources>
""".trimIndent()
)

loadRealProjects()

val intent = Intent(this, MainActivity::class.java)
intent.putExtra("PROJECT_PATH", newProjectDir.absolutePath)
startActivity(intent)
} else {
Toast.makeText(this, "Failed to create directory.", Toast.LENGTH_LONG).show()
}
} else {
Toast.makeText(this, "Project already exists!", Toast.LENGTH_SHORT).show()
}
} catch (e: Exception) {
Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
}
}
// --- 3. THE MASTER PERMISSIONS ENGINE ---
private fun requestMasterStorageAccess() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
if (!Environment.isExternalStorageManager()) {
try {
// This forces the specific "All Files Access" screen to open
val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
intent.data = Uri.parse("package:${applicationContext.packageName}")
startActivity(intent)
} catch (e: Exception) {
startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
}
} else {
loadRealProjects()
}
} else {
if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
} else {
loadRealProjects()
}
}
}

override fun onResume() {
super.onResume()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
if (Environment.isExternalStorageManager()) loadRealProjects()
} else {
if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
loadRealProjects()
}
}
}

// --- 4. 3-DOT MENU LOGIC ---
override fun onCreateOptionsMenu(menu: Menu?): Boolean {
menu?.add(Menu.NONE, 1001, Menu.NONE, "About")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
if (item.itemId == 1001) {
AlertDialog.Builder(this)
.setTitle("About")
.setMessage("Adept Native IDE\n\nMade by Saras\n\nVersion 1.0 Alpha")
.setPositiveButton("Close", null)
.show()
return true
}
return super.onOptionsItemSelected(item)
}
// 💥 NEW: Safe Delete Engine
private fun showDeleteConfirmation(projectName: String) {
AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
.setTitle("Delete Project?")
.setMessage("Are you sure you want to permanently delete '$projectName'? This will erase all files inside it.")
.setPositiveButton("Delete") { _, _ ->
val projectDir = File(workspacePath, projectName)
if (projectDir.exists()) {
// deleteRecursively() wipes the folder and everything inside it instantly
val deleted = projectDir.deleteRecursively()
if (deleted) {
Toast.makeText(this, "$projectName deleted.", Toast.LENGTH_SHORT).show()
loadRealProjects() // Refresh the UI list
} else {
Toast.makeText(this, "Failed to delete project.", Toast.LENGTH_SHORT).show()
}
}
}
.setNegativeButton("Cancel", null)
.show()
}
// --- 5. RECYCLER VIEW ADAPTER ---
inner class ProjectAdapter(
private val projects: List<String>,
private val displayPath: String,
private val onProjectClicked: (String) -> Unit,
private val onProjectLongClicked: (String) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.ProjectViewHolder>() {

inner class ProjectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
val txtProjectName: TextView = view.findViewById(R.id.txtProjectName)
val txtWorkspaceText: TextView = view.findViewById(R.id.txtWorkspaceText)
// We removed the 'root' variable here to use the native 'itemView' below
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
return ProjectViewHolder(view)
}

override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
val projectName = projects[position]
holder.txtProjectName.text = projectName
holder.txtWorkspaceText.text = "$displayPath/$projectName"

// 💥 THE FIX: Attach listeners directly to itemView
holder.itemView.setOnClickListener {
onProjectClicked(projectName)
}

holder.itemView.setOnLongClickListener {
onProjectLongClicked(projectName)
true
}
}

override fun getItemCount() = projects.size
}
}