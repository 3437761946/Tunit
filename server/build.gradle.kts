// Re-NotTiled 无头开服模块：纯中继 + 多房间管理（2 房/每房 6 人/总连接 10，可在 server.properties 调）
plugins {
    java
    application
}

// 与客户端同一份协议类源码（NotTiled core/src），只抽取 6 个 wire class，
// 保证 FQCN、字段顺序与 Kryo 注册顺序和客户端完全一致，避免跨端反序列化失败。
val ntSrc = "../NotTiled_src/NotTiled-1.8.6"
val protocol = sourceSets.create("protocol")
protocol.java.srcDir(file("$ntSrc/core/src"))
protocol.java.include(
    "**/packet.java",
    "**/layerhistory.java",
    "**/TextChat.java",
    "**/command.java",
    "**/PlayerState.java",
    "**/actvClients.java"
)

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// ===== 示例插件源集 =====
// 插件源码放在 src/pluginsSample/java，编译时引用 main（含插件 API/协议类）的编译产物；
// 打包产出轻量 jar（不内嵌依赖，运行时由服务器 fat jar 提供父级类加载）。
val pluginsSample by sourceSets.creating {
    java.srcDir("src/pluginsSample/java")
}
// 让 pluginsSample 能看到 main 的编译产物与全部运行依赖（协议类在 main 的 implementation 里）
configurations.getByName("pluginsSampleImplementation").extendsFrom(configurations.getByName("implementation"))
dependencies {
    "pluginsSampleImplementation"(sourceSets.main.get().output)
}

dependencies {
    // 与 app 模块一致：kryonet 排除传递的 kryo 5.x，显式使用 kryo 4.0.1
    implementation("com.esotericsoftware:kryo:4.0.1")
    implementation("com.esotericsoftware:kryonet:2.22.0-RC1") {
        exclude(group = "com.esotericsoftware.kryo", module = "kryo")
    }
    // protocol 源集同样需要 kryo/kryonet（actvClients.java 新增 conn 字段引用 kryonet.Connection）
    "protocolImplementation"("com.esotericsoftware:kryo:4.0.1")
    "protocolImplementation"("com.esotericsoftware:kryonet:2.22.0-RC1") {
        exclude(group = "com.esotericsoftware.kryo", module = "kryo")
    }
    // 让 main 源集依赖 protocol 源集编译产物（同时建立 compileProtocolJava 任务依赖）
    implementation(protocol.output)
}

application {
    mainClass.set("com.mirwanda.nottiled.server.ReNotTiledServer")
}

// 单文件 fat jar（协议类 + 服务器类 + 全部运行依赖）
tasks.jar {
    archiveBaseName.set("Re-NotTiled-Server")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    from(protocol.output)
    from(configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    dependsOn(tasks.named("classes"))
}

// 拷贝 jar 到顶层 release_server 部署目录
val deployDir = rootProject.layout.projectDirectory.dir("release_server")
val deployPluginDir = rootProject.layout.projectDirectory.dir("release_server/plugins")

// 打包示例插件（仅插件类，不内嵌任何依赖；运行时由服务器父类加载器提供协议/API 类）
val samplePluginJar by tasks.registering(Jar::class) {
    archiveBaseName.set("sampleplugin")
    archiveVersion.set("")
    from(pluginsSample.output)
    dependsOn(tasks.named("pluginsSampleClasses"))
}

// 把示例插件拷到 release_server/plugins/sampleplugin.jar
val deploySamplePlugin by tasks.registering(Copy::class) {
    dependsOn(samplePluginJar)
    from(samplePluginJar)
    into(deployPluginDir)
    rename { "sampleplugin.jar" }
}

tasks.register<Copy>("deployServer") {
    dependsOn(tasks.named("jar"), deploySamplePlugin)
    from(tasks.named("jar"))
    into(deployDir)
    rename { "Re-NotTiled-Server.jar" }
}
