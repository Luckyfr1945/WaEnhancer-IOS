package com.wmods.wppenhacer.utils

object VersionUtils {

    /**
     * Menentukan apakah [targetTag] merupakan versi baru dibandingkan dengan [currentVersion].
     *
     * @param currentVersion Versi aplikasi saat ini, misalnya "1.6.0 (32E89AC6)", "1.6.0", dll.
     * @param targetTag Nama tag rilis dari GitHub, misalnya "v1.6.0", "1.6.0", "1.5.7-91e12839", dll.
     * @return true jika targetTag lebih baru daripada currentVersion, false jika sebaliknya.
     */
    @JvmStatic
    fun isNewVersion(currentVersion: String?, targetTag: String?): Boolean {
        if (targetTag.isNullOrBlank()) return false
        if (currentVersion.isNullOrBlank()) return true

        // Bersihkan awalan huruf 'v' atau 'V' dan spasi
        val cleanTag = targetTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        // Pisahkan bagian semver dari hash commit / prerelease
        // Contoh tag: "1.6.0", "1.5.7-91e12839", "1.6.0_32E89AC6"
        val tagParts = cleanTag.split('-', '_')
        val tagSemver = tagParts[0].trim()
        val tagExtra = if (tagParts.size > 1) tagParts[1].trim() else ""

        // Contoh current: "1.6.0 (32E89AC6)", "1.6.0"
        val currentSemver = cleanCurrent.substringBefore('(').substringBefore('-').substringBefore('_').trim()

        val tagNumbers = parseVersionNumbers(tagSemver)
        val currentNumbers = parseVersionNumbers(currentSemver)

        // Bandingkan komponen numerik: major, minor, patch, dst.
        val maxLen = maxOf(tagNumbers.size, currentNumbers.size)
        for (i in 0 until maxLen) {
            val t = if (i < tagNumbers.size) tagNumbers[i] else 0
            val c = if (i < currentNumbers.size) currentNumbers[i] else 0
            if (t > c) return true
            if (t < c) return false
        }

        // Jika nomor semver identik (misal 1.5.7 == 1.5.7):
        // Jika remote memiliki commit hash khusus (misal rilis nightly/dev "1.5.7-91e12839")
        // dan versi lokal belum memuat hash tersebut:
        if (tagExtra.isNotBlank()) {
            val normCurrent = cleanCurrent.lowercase()
            val normTagExtra = tagExtra.lowercase()
            if (!normCurrent.contains(normTagExtra)) {
                return true
            }
        }

        return false
    }

    private fun parseVersionNumbers(versionStr: String): List<Int> {
        return versionStr.split('.')
            .map { segment ->
                val digits = segment.takeWhile { it.isDigit() }
                digits.toIntOrNull() ?: 0
            }
    }
}
