package com.yeivikas.olyze.eliner.resources

/**
 * The kinds of resource EliNer will manage, matching exactly the folders
 * named in the Fase 2 spec (Samples/, Instruments/, Plugins/, Presets/,
 * Automation/, Record/, MIDI/, Metadata/). Nothing more was added — this
 * is the exhaustive list from the spec, not a guess at future categories.
 */
enum class ResourceCategory {
    SAMPLE, INSTRUMENT, PLUGIN, PRESET, AUTOMATION, RECORD, MIDI, METADATA
}

/**
 * Identifies a resource without saying anything about where it lives or
 * what it contains — e.g. `ResourceId(SAMPLE, "kick_909")`. [key] is
 * opaque to this layer; whatever owns a given [ResourceCategory] decides
 * its own key format (a file name, a UUID, whatever fits).
 */
data class ResourceId(val category: ResourceCategory, val key: String)

/**
 * Where a resource can be found, once a [ResourceProvider] has resolved a
 * [ResourceId]. [uri] is a plain string on purpose — this phase does not
 * implement loading, so there's no real value in choosing a concrete URI
 * type (`android.net.Uri`, `java.net.URI`, a raw path) before something
 * actually needs to open it. Whichever module implements real loading
 * decides that later without this contract needing to change shape.
 */
data class ResourceLocation(val id: ResourceId, val uri: String)
