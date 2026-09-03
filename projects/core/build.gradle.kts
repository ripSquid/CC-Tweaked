// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

import cc.tweaked.gradle.CCTweakedPlugin
import cc.tweaked.gradle.getAbsolutePath
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    `java-library`
    `java-test-fixtures`
    kotlin("jvm")

    id("cc-tweaked.java-convention")
    id("cc-tweaked.publishing")
    id("cc-tweaked")
}

val modVersion: String by extra

dependencies {
    api(project(":core-api"))
    implementation(libs.cobalt)
    implementation(libs.endive.runtime)
    implementation(libs.endive.wasi)
    compileOnly(libs.endive.annotations)
    implementation(libs.fastutil)
    implementation(libs.guava)
    implementation(libs.netty.http)
    implementation(libs.netty.socks)
    implementation(libs.netty.proxy)
    implementation(libs.slf4j)

    testFixturesImplementation(libs.slf4j)
    testFixturesApi(platform(libs.kotlin.platform))
    testFixturesApi(libs.bundles.test)
    testFixturesApi(libs.bundles.kotlin)

    testImplementation(libs.asm)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.bundles.testRuntime)
    testRuntimeOnly(libs.slf4j.simple)
}

kotlin.compilerOptions.jvmTarget = CCTweakedPlugin.KOTLIN_TARGET

val buildLuaWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Rebuild the bundled Lua Wasm boot disk with WASI SDK 34."
    commandLine("python3", "wasm/build.py")
}

val verifyLuaWasm by tasks.registering {
    group = "verification"
    description = "Verify bundled Wasm matches guest sources, without a native compiler."
    val manifest = layout.projectDirectory.file("wasm/build.sha256")
    val root = layout.projectDirectory
    mustRunAfter(buildLuaWasm)
    doLast {
        manifest.asFile.forEachLine { line ->
            val (expected, name) = line.split("  ", limit = 2)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(root.file(name).asFile.readBytes())
            check(HexFormat.of().formatHex(digest) == expected) {
                "Stale Wasm image: $name changed. Run ./gradlew :core:buildLuaWasm."
            }
        }
    }
}

tasks.processResources {
    dependsOn(verifyLuaWasm)
    inputs.property("gitHash", cct.gitHash)

    var props = mapOf("gitContributors" to cct.gitContributors.get().joinToString("\n"))
    filesMatching("data/computercraft/lua/rom/help/credits.md") { expand(props) }
}

tasks.test {
    systemProperty("cct.test-files", layout.buildDirectory.dir("tmp/testFiles").getAbsolutePath())
}

val checkChangelog by tasks.registering(cc.tweaked.gradle.CheckChangelog::class) {
    version = modVersion
    whatsNew = file("src/main/resources/data/computercraft/lua/rom/help/whatsnew.md")
    changelog = file("src/main/resources/data/computercraft/lua/rom/help/changelog.md")
}

tasks.check { dependsOn(checkChangelog) }

cct.linters(minecraft = false, loader = null)
