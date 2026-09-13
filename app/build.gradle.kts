// NotTiled 1.8.6 —— 引用官方开源源码编译
import java.util.Properties
plugins {
    id("com.android.application")
}

// v1.8.6 官方开源源码目录（相对 app 模块）
val ntSrc = "../NotTiled_src/NotTiled-1.8.6"

android {
    namespace = "com.mirwanda.nottiled"
    compileSdk = 37

    // 生成 BuildConfig：用于把「服务器地址」在构建期注入（公开源码里只留占位符）
    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("$ntSrc/android/AndroidManifest.xml")
            // 使用真实的源码根目录，保证包路径与 com.xxx 声明一致（IDE 逐个包校验）
            java.setSrcDirs(listOf("$ntSrc/core/src", "$ntSrc/android/src", "../NotTiled_src/vendored/noise4j"))
            // 排除内嵌的平台副本（平台已提供 android.util.Base64、org.xmlpull.v1），
            // 通过源集过滤器排除，构建与 IDE 索引都会生效，避免 split-package 冲突
            (java as org.gradle.api.tasks.util.PatternFilterable).exclude(
                "org/xmlpull/**",
                "org/kxml2/**",
                "libcore/**",
                "android/util/Base64.java"
            )
            assets.setSrcDirs(listOf("$ntSrc/android/assets"))
            res.setSrcDirs(listOf("$ntSrc/android/res"))
        }
    }

    defaultConfig {
        // 应用标识（设备/商店上的唯一身份）：不再占用上游 com.mirwanda 命名空间。
        // 注意：Java 包名(namespace)保持 com.mirwanda.nottiled 不变，仅应用标识独立。
        applicationId = "com.momo.tunit"
        minSdk = 19
        targetSdk = 33
        versionCode = 94
        versionName = "1.8.12"

        // 服务器地址（构建期注入）：真实 IP 只放在被 .gitignore 排除的 app/server.properties；
        // 公开源码中 BuildConfig.SERVER_HOST 仅编译为占位符 0.0.0.0，避免泄露真实服务器地址。
        // 发布时：本地写好 server.properties 后正常构建即可，源码公开无需改动。
        val serverPropsFile = file("server.properties")
        val serverProps = Properties().apply {
            if (serverPropsFile.exists()) serverPropsFile.inputStream().use { load(it) }
        }
        val serverHost = serverProps.getProperty("serverHost")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0.0.0.0"
        val serverPort = serverProps.getProperty("serverPort")?.trim()?.toIntOrNull() ?: 40686
        buildConfigField("String", "SERVER_HOST", "\"$serverHost\"")
        buildConfigField("int", "SERVER_PORT", serverPort.toString())
    }

    // ================== 签名配置（私钥严禁入库）==================
    // 正式签名从 app/keystore/keystore.properties 读取；该文件与 *.jks/*.keystore 均已被 .gitignore 排除。
    // 目的：统一签名，保证 OTA 覆盖安装（否则 INSTALL_FAILED_UPDATE_INCOMPATIBLE）；同时杜绝私钥随仓库公开。
    // 生成正式密钥（一次即可，命令示例）：
    //   keytool -genkeypair -v -keystore app/keystore/release.jks -storetype JKS \
    //     -keyalg RSA -keysize 2048 -validity 10000 -alias tunit \
    //     -storepass <口令> -keypass <口令> -dname "CN=Tunit, O=Tunit, C=CN"
    // 请把 release.jks 与 keystore.properties 一起离线备份；丢失后无法用同一签名覆盖安装。
    val keystorePropsFile = file("keystore/keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        create("pinned") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                // 回退：仅用于本机无正式密钥时的调试构建（不可用于对外发布）。
                logger.warn("[signing] 未找到 keystore/keystore.properties，回退到本地调试签名（请勿用于发布）。")
                storeFile = file("keystore/debug.jks")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pinned")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("pinned")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "META-INF/robovm/ios/robovm.xml"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

// libGDX 原生库配置（gdx 原生 .so 打包在 platform jar 里，需解出到 jniLibs）
val natives by configurations.creating

dependencies {
    // libGDX 核心 + 扩展
    implementation("com.badlogicgames.gdx:gdx:1.11.0")
    implementation("com.badlogicgames.gdx:gdx-backend-android:1.11.0")
    implementation("com.badlogicgames.gdx:gdx-freetype:1.11.0")
    implementation("com.badlogicgames.gdx:gdx-box2d:1.11.0")
    implementation("com.badlogicgames.gdx:gdx-ai:1.8.2")

    // 第三方依赖
    implementation("com.badlogicgames.box2dlights:box2dlights:1.5")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-android:2.2.1")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.1")
    implementation("de.tomgrill.gdxdialogs:gdx-dialogs-android:1.3.0")
    implementation("de.tomgrill.gdxdialogs:gdx-dialogs-core:1.3.0")
    implementation("com.esotericsoftware:kryo:4.0.1")
    implementation("com.esotericsoftware:kryonet:2.22.0-RC1") {
        // 排除 kryonet 传递的 kryo 5.x，使用源码依赖的 kryo 4.0.1，避免同名包冲突
        exclude(group = "com.esotericsoftware.kryo", module = "kryo")
    }
    implementation("org.bouncycastle:bcpkix-jdk15on:1.56")

    // 本地 jar（jfugue for android + javaxmidi）
    implementation(files("$ntSrc/android/libs/jfugue-android.jar", "$ntSrc/android/libs/javaxmidi.jar"))

    // 原生库（.so 由下方任务解出到 src/main/jniLibs）
    natives("com.badlogicgames.gdx:gdx-platform:1.11.0:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:1.11.0:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:1.11.0:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:1.11.0:natives-x86_64")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:1.11.0:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:1.11.0:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:1.11.0:natives-x86")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:1.11.0:natives-x86_64")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:1.11.0:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:1.11.0:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:1.11.0:natives-x86")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:1.11.0:natives-x86_64")
}

// 从 gdx 原生 jar 中解出各 ABI 的 .so 到 src/main/jniLibs
val copyAndroidNatives by tasks.registering {
    val nativesConfig = configurations.getByName("natives")
    val outRoot = file("src/main/jniLibs")
    doFirst {
        outRoot.mkdirs()
        nativesConfig.files.forEach { jar ->
            val abi = when {
                jar.name.contains("natives-arm64-v8a") -> "arm64-v8a"
                jar.name.contains("natives-armeabi-v7a") -> "armeabi-v7a"
                jar.name.contains("natives-x86_64") -> "x86_64"
                jar.name.contains("natives-x86") -> "x86"
                else -> null
            }
            if (abi != null) {
                val destDir = outRoot.resolve(abi)
                destDir.mkdirs()
                copy {
                    from(zipTree(jar))
                    into(destDir)
                    include("*.so")
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyAndroidNatives)
}
