package com.yeivikas.olyze.eliner.core

/**
 * Identifies a build of the EliNer engine: name, semantic version, build
 * label, build date, and the internal API level future modules will need
 * to check compatibility against.
 *
 * [minCompatibleApi] exists for one reason: once real modules exist, a
 * module built against an older Core API could be registered against a
 * newer engine. Rather than inventing a compatibility system now, this
 * field is the one piece of information a future compatibility check would
 * need — the mechanism itself belongs to a later phase, once there's a
 * second API version to actually compare against.
 */
data class EngineVersion(
    val name: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: String,
    val buildDate: String,
    val minCompatibleApi: Int,
) {
    val versionString: String get() = "$major.$minor.$patch"

    override fun toString(): String = "$name $versionString (build $build, $buildDate)"

    companion object {
        /**
         * The version of *this* build of EliNer's Core Foundation.
         *
         * `minCompatibleApi = 1` because this phase defines API level 1 —
         * there is nothing older to be compatible with yet. Bump this
         * deliberately when a future phase makes a breaking change to
         * [EliNerModule] or [EliNerCore]'s public surface.
         */
        val CURRENT = EngineVersion(
            name = "EliNer",
            major = 0,
            minor = 1,
            patch = 0,
            build = "core-foundation",
            buildDate = "2026-08-02",
            minCompatibleApi = 1,
        )
    }
}
