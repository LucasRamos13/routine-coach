import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------------------------
// Content pack injection: the public build bundles the demo pack. A private pack is selected with
// `routineCoach.packDir=/path/to/pack` in local.properties (never committed) or -ProutineCoach.packDir=...
// ---------------------------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val packDir: File = (providers.gradleProperty("routineCoach.packDir").orNull
    ?: localProps.getProperty("routineCoach.packDir"))
    ?.let { rootProject.file(it) }
    ?: rootProject.file("content/demo-pack")
val libraryDir: File = rootProject.file("content/library")
val packAssets = layout.buildDirectory.dir("generated/packAssets")

val validatePack = tasks.register<JavaExec>("validatePack") {
    description = "Validates the content pack bundled into the app."
    dependsOn(":tools:installDist")
    classpath = fileTree(project(":tools").layout.buildDirectory.dir("install/routine-coach-tools/lib"))
    mainClass.set("dev.routinecoach.tools.MainKt")
    args("validate", "--pack", packDir.absolutePath, "--library", libraryDir.absolutePath)
    inputs.dir(packDir)
    inputs.dir(libraryDir)
    outputs.upToDateWhen { true }
}

val copyPackAssets = tasks.register<Sync>("copyPackAssets") {
    dependsOn(validatePack)
    from(libraryDir) { into("library") }
    from(packDir) {
        into("pack")
        exclude("**/.gitkeep", "**/README*")
    }
    into(packAssets)
}

android {
    namespace = "dev.routinecoach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.routinecoach.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded builds are signed with the debug key unless a release key is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    sourceSets {
        getByName("main").assets.srcDir(packAssets)
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.named("preBuild") { dependsOn(copyPackAssets) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
