import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace="com.offgrid.messenger"
 compileSdk=36
 defaultConfig { applicationId="com.offgrid.messenger"; minSdk=26; targetSdk=36; versionCode=242; versionName="2.4.2-stability" }
 buildFeatures { compose=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }

}

// Kotlin 2.4+: the old android.kotlinOptions.jvmTarget String DSL is an error.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
 val bom=platform("androidx.compose:compose-bom:2026.06.01")
 implementation(bom)
 // Haze 2.1: source-backed backdrop blur, not a blur of the card text.
 implementation("dev.chrisbanes.haze:haze:2.1.0")
 implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
}
