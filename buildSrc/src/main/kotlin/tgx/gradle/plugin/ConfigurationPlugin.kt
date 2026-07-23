/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package tgx.gradle.plugin

import ApplicationConfig
import PullRequest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra
import tgx.gradle.getIntOrThrow
import tgx.gradle.getOrThrow
import tgx.gradle.loadProperties

open class ConfigurationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val properties = loadProperties()
    val sampleProperties = loadProperties("local.properties.sample")
    val safetyNetToken = properties.getProperty("safetynet.api_key", "")
    fun getOrSample (key: String): String {
      return properties.getProperty(key, null) ?: sampleProperties.getOrThrow(key)
    }
    val applicationId = getOrSample("app.id")
    val applicationName = getOrSample("app.name")
    val appDownloadUrl = getOrSample("app.download_url")
    val googlePlayUrl = properties.getProperty("app.google_download_url", null)
    val galaxyStoreUrl = properties.getProperty("app.galaxy_download_url", null)
    val huaweiAppGalleryUrl = properties.getProperty("app.huawei_download_url", null)
    val amazonAppStoreUrl = properties.getProperty("app.amazon_download_url", null)
    val isExampleBuild = applicationId.startsWith("com.example.") || applicationId.startsWith("org.example.")
    val isExperimentalBuild = isExampleBuild || properties.getProperty("app.experimental", "false") == "true"
    val doNotObfuscate = isExampleBuild || properties.getProperty("app.dontobfuscate", "false") == "true"
    val forceOptimize = properties.getProperty("app.forceoptimize") == "true"
    val appExtension = getOrSample("tgx.extension")
    if (appExtension != "none" && appExtension != "hms") {
      error("Unknown tgx.extension: $appExtension")
    }
    val isHuaweiBuild = appExtension == "hms"

    val versions = loadProperties("version.properties")

    val compileSdkVersion = versions.getIntOrThrow("version.sdk_compile")
    val buildToolsVersion = versions.getOrThrow("version.build_tools")
    val targetSdkVersion = versions.getIntOrThrow("version.sdk_target")

    val legacyNdkVersion = versions.getOrThrow("version.ndk_legacy")
    val primaryNdkVersion = versions.getOrThrow("version.ndk_primary")

    val nativeLibraryVersion = versions.getProperty("version.jni")
    val leveldbVersion = versions.getProperty("version.leveldb")
    val emojiVersion = versions.getIntOrThrow("version.emoji")

    val telegramApiId = 105810
    val telegramApiHash = "3e7a52498eec003c5896a330e5d29397"

    val creationDateMillis = versions.getOrThrow("version.creation").toLong()

    val pullRequests = properties.getProperty("pr.ids", "").split(',').filter { it.matches(Regex("^[0-9]+$")) }.map {
      PullRequest(it.toLong(), properties)
    }.sortedBy { it.id }

    val defaultFileNamePrefix = applicationName.replace(" ", "-").replace("#", "")
    val outputFileNamePrefix = properties.getProperty("app.file", defaultFileNamePrefix)

    val appVersionOverride = properties.getProperty("app.version", "0").toInt()
    val applicationVersion = if (appVersionOverride > 0) appVersionOverride else versions.getOrThrow("version.app").toInt()
    val majorVersion = versions.getOrThrow("version.major").toInt()

    val sourceCodeUrl = properties.getProperty("app.sources_url", "")

    val config = ApplicationConfig(
      applicationName,
      applicationId,
      appExtension,
      sourceCodeUrl,
      applicationVersion,
      majorVersion,
      isExperimentalBuild,
      isHuaweiBuild,
      forceOptimize,
      doNotObfuscate,
      compileSdkVersion,
      targetSdkVersion,
      buildToolsVersion,
      legacyNdkVersion,
      primaryNdkVersion,
      nativeLibraryVersion,
      leveldbVersion,
      emojiVersion,

      telegramApiId,
      telegramApiHash,
      safetyNetToken,
      appDownloadUrl,
      googlePlayUrl,
      galaxyStoreUrl,
      huaweiAppGalleryUrl,
      amazonAppStoreUrl,

      pullRequests,

      outputFileNamePrefix,
      creationDateMillis
    )
    project.extra.set("config", config)
  }
}