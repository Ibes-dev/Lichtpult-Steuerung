import com.google.protobuf.gradle.id

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

val appName = (project.findProperty("appName") as String?)?.trim()?.ifBlank { null } ?: "Lichtpult Remote"
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

val grpcVersion = "1.68.1"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "3.25.5"

android {
    namespace = "de.sebi.dmxc3remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.sebi.dmxc3remote"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.0"
        resValue("string", "app_name", appName)
        buildConfigField("String", "APP_NAME", "\"$appName\"")
    }

    signingConfigs {
        // Fester Debug-Schlüssel im Repo: sonst erzeugt jeder CI-Lauf einen neuen, Android verweigert
        // das Update, die App muss deinstalliert werden und verliert Runtime-ID und Softdesks.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
            }
            task.plugins {
                id("grpc") { option("lite") }
                id("grpckt") { option("lite") }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf-lite:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("junit:junit:4.13.2")
}
