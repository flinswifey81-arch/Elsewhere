package com.example

import com.example.domain.world.*
import org.junit.Assert.*
import org.junit.Test

class WorldDefinitionCodecTest {
    private fun example() = WorldDefinitionV1(
        format = WorldDefinitionCodec.FORMAT,
        schemaVersion = WorldDefinitionCodec.SCHEMA_VERSION,
        world = WorldMetadataV1(
            worldId = "mondstadt",
            title = "Law & Disorder: Mondstadt",
            settingSummary = "Modern nonmagical Teyvat with Omegaverse biology",
            genreTags = listOf("procedural", "romantic-comedy")
        ),
        resources = WorldResourcesV1(
            worldCast = listOf(
                WorldCastV1("varka", "Varka", profile = mapOf("identity" to "Police Commissioner")),
                WorldCastV1("flins", "Flins", profile = mapOf("identity" to "District Attorney"))
            )
        ),
        links = WorldLinksV1(
            relationships = listOf(WorldRelationshipV1("varka", "flins", "Established mated couple"))
        ),
        continuity = listOf(WorldContinuityV1("city-01", "City charter", "Mondstadt has a mayor.", "ACCEPTED",
            relatedWorldCharacterIds = listOf("varka")))
    )

    @Test fun roundTripPreservesWorldCastAndRelationship() {
        val json = WorldDefinitionCodec.encode(example())
        val decoded = WorldDefinitionCodec.parse(json)
        assertEquals(example(), decoded)
        assertTrue(json.contains("world_character_id"))
        assertTrue(json.contains("schema_version"))
    }

    @Test fun rejectsRelationshipToUnknownWorldCharacter() {
        val invalid = example().copy(links = WorldLinksV1(
            relationships = listOf(WorldRelationshipV1("varka", "stranger", "Unknown person"))
        ))
        assertThrows(IllegalArgumentException::class.java) { WorldDefinitionCodec.encode(invalid) }
    }

    @Test fun rejectsDuplicateCastAndContinuityIds() {
        val e = example()
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.encode(e.copy(resources = e.resources.copy(
                worldCast = e.resources.worldCast + e.resources.worldCast.first())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.encode(e.copy(continuity = e.continuity + e.continuity.first()))
        }
    }

    @Test fun rejectsUnapprovedContinuityFromDefinition() {
        val e = example()
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.encode(e.copy(continuity = e.continuity.map { it.copy(status = "DRAFT") }))
        }
    }

    @Test fun rejectsUnrecognizedFormatUnknownFieldsAndExcessiveJson() {
        val json = WorldDefinitionCodec.encode(example())
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.parse(json.replace("elsewhere_world_definition", "elsewhere_world_archive"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.parse(json.replaceFirst("\"format\":", "\"unexpected_root\": true, \"format\":"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.parse(" ".repeat(WorldDefinitionCodec.MAX_JSON_CHARS + 1))
        }
    }

    @Test fun rejectsUnresolvedScenarioRole() {
        val e = example()
        assertThrows(IllegalArgumentException::class.java) {
            WorldDefinitionCodec.encode(e.copy(links = WorldLinksV1(castRoles = listOf(
                WorldCastRoleV1("varka", "SCENARIO_NPC", "npc-1", "missing-scenario")
            ))))
        }
    }
}
