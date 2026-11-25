plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.routinecoach.tools.MainKt")
    applicationName = "routine-coach-tools"
}

dependencies {
    implementation(project(":core"))
}
