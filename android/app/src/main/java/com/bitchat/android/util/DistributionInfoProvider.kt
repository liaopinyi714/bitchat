package com.bitchat.android.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bitchat.android.BuildConfig
import com.bitchat.android.R
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Read-only diagnostics describing how the currently running app was packaged
 * and installed. These values are facts about the installed artifact, not
 * settings that can be changed at runtime.
 */
object DistributionInfoProvider {
    private val UNIVERSAL_RELEASE_ABIS = setOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    fun inspect(sourceContext: Context): DistributionInfo {
        val context = ContextCompat.getContextForLanguage(sourceContext)
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            signingFlags()
        )
        val applicationInfo = context.applicationInfo
        val splitApks = applicationInfo.splitSourceDirs.orEmpty()
        val installerPackage = installerPackageName(context)
        val certificateSha256 = signingCertificateSha256(packageInfo)
        val installedApkVariant = if (splitApks.isEmpty()) {
            shareableApkVariant(File(applicationInfo.sourceDir))
        } else {
            null
        }

        return DistributionInfo(
            installSource = installSourceLabel(context, installerPackage),
            installerPackage = installerPackage,
            packageFormat = if (splitApks.isEmpty()) context.getString(R.string.debug_ui_standalone) else context.getString(R.string.debug_ui_split),
            architecture = architectureLabel(context, applicationInfo.sourceDir, splitApks),
            sharingSource = when (installedApkVariant) {
                ShareableApkVariant.UNIVERSAL -> context.getString(R.string.debug_ui_installed_apk)
                ShareableApkVariant.ARM64 -> context.getString(R.string.debug_ui_installed_arm64)
                null -> context.getString(R.string.debug_ui_github_apk)
            },
            versionName = packageInfo.versionName ?: BuildConfig.VERSION_NAME,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            signingChannel = signingChannel(context, installerPackage, certificateSha256),
            certificateSha256 = certificateSha256
        )
    }

    private fun installerPackageName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun installSourceLabel(context: Context, installerPackage: String?): String {
        return when (installerPackage) {
            "com.android.vending" -> "Google Play"
            "com.amazon.venezia" -> "Amazon Appstore"
            "org.fdroid.fdroid" -> "F-Droid"
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller" -> context.getString(R.string.debug_ui_package_installer)
            null -> if (BuildConfig.DEBUG) context.getString(R.string.debug_ui_adb_install) else context.getString(R.string.debug_ui_unknown_install)
            else -> installerPackage
        }
    }

    private fun architectureLabel(context: Context, baseApkPath: String, splitApkPaths: Array<out String>): String {
        val apkPaths = listOf(baseApkPath) + splitApkPaths
        val packagedAbis = buildSet {
            apkPaths.forEach { path ->
                addAll(nativeAbisInApk(File(path)))
                addAll(abisInSplitName(File(path).name))
            }
        }

        return when {
            packagedAbis.containsAll(UNIVERSAL_RELEASE_ABIS) ->
                context.getString(R.string.debug_ui_universal, packagedAbis.joinToString())
            packagedAbis.size > 1 -> context.getString(R.string.debug_ui_multi_abi, packagedAbis.joinToString())
            packagedAbis.size == 1 -> packagedAbis.single()
            splitApkPaths.isNotEmpty() -> context.getString(R.string.debug_ui_device_abi, Build.SUPPORTED_ABIS.firstOrNull() ?: context.getString(R.string.debug_ui_unknown))
            else -> context.getString(R.string.debug_ui_no_native)
        }
    }

    /**
     * An APK with no native payload works across ABIs. When native libraries
     * are present, require every ABI produced by the release workflow.
     */
    fun isUniversalApk(apk: File): Boolean {
        val packagedAbis = nativeAbisInApk(apk)
        return packagedAbis.isEmpty() || packagedAbis.containsAll(UNIVERSAL_RELEASE_ABIS)
    }

    /**
     * Returns the compatibility of an APK that is safe to offer for sharing.
     * ARM64 is intentionally the only architecture-limited release variant
     * supported because it is the project's primary per-ABI build.
     */
    fun shareableApkVariant(apk: File): ShareableApkVariant? {
        val packagedAbis = nativeAbisInApk(apk)
        return when {
            packagedAbis.isEmpty() || packagedAbis.containsAll(UNIVERSAL_RELEASE_ABIS) ->
                ShareableApkVariant.UNIVERSAL
            packagedAbis == setOf("arm64-v8a") -> ShareableApkVariant.ARM64
            else -> null
        }
    }

    internal fun nativeAbisInApk(apk: File): Set<String> {
        if (!apk.isFile) return emptySet()
        return try {
            ZipFile(apk).use { zip ->
                buildSet {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val path = entries.nextElement().name
                        if (path.startsWith("lib/")) {
                            path.split('/').getOrNull(1)
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun abisInSplitName(fileName: String): Set<String> {
        val normalizedName = fileName.replace('_', '-')
        return Build.SUPPORTED_ABIS
            .filter { abi -> normalizedName.contains(abi.replace('_', '-'), ignoreCase = true) }
            .toSet()
    }

    private fun signingChannel(context: Context, installerPackage: String?, certificateSha256: String?): String {
        if (BuildConfig.DEBUG) return context.getString(R.string.debug_ui_debug_signing)
        if (installerPackage == "com.android.vending") return "Google Play"

        val pinnedGitHubCert = BuildConfig.GITHUB_RELEASE_CERT_SHA256
            .replace(":", "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        return if (certificateSha256 != null && certificateSha256 == pinnedGitHubCert) {
            context.getString(R.string.debug_ui_github_release)
        } else {
            context.getString(R.string.debug_ui_unknown_release)
        }
    }

    private fun signingCertificateSha256(packageInfo: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        val signature = signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    data class DistributionInfo(
        val installSource: String,
        val installerPackage: String?,
        val packageFormat: String,
        val architecture: String,
        val sharingSource: String,
        val versionName: String,
        val versionCode: Long,
        val signingChannel: String,
        val certificateSha256: String?
    )
}

enum class ShareableApkVariant {
    UNIVERSAL,
    ARM64
}
