// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}

// Copy debug APKs to the repo dist folder: OOJOO-FARM/dist
subprojects {
    afterEvaluate {
        tasks.findByName("assembleDebug")?.doLast {
            val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
            if (!apk.exists()) return@doLast
            val destDir = rootProject.projectDir.resolve("..").normalize().resolve("dist")
            destDir.mkdirs()
            val name = if (path.contains("slave")) "oojoo-farm-farmer-debug.apk" else "oojoo-farm-master-debug.apk"
            apk.copyTo(destDir.resolve(name), overwrite = true)
        }
    }
}
