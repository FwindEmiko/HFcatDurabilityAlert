plugins {
    java
}

group = "io.github.fcestial"
version = "1.2.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 编译基线固定为 1.20.6（组件化耐久 API 起点 1.20.5 的可解析最近版本；
    // 1.20.5 的 POM 依赖的 adventure-bom:4.17.0-SNAPSHOT 已被仓库清理，无法解析）。
    // 源码中只使用 1.20.5 就已存在的 API（见 README「兼容性」表），因此运行期覆盖 1.20.5 ~ 26.2。
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        // 输出 Java 21 字节码：1.21.x（Java 21）与 26.x（Java 25）服务端都能加载
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version.toString())
        }
    }
    jar {
        archiveBaseName.set("HFcatDurabilityAlert")
    }
}
