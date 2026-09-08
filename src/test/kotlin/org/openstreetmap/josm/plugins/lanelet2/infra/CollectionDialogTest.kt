package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.osm.RelationMember
import org.openstreetmap.josm.plugins.lanelet2.platform.Dialogs
import org.openstreetmap.josm.plugins.lanelet2.platform.LaneletSettings
import org.openstreetmap.josm.plugins.lanelet2.regulatory.CreateRightOfWayRelation
import org.openstreetmap.josm.plugins.lanelet2.regulatory.DebugRightOfWayWizard
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences

class CollectionDialogTest {

    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    @Test
    fun basketAddsUniquesAndRecordsUndoBatches() {
        val a = OsmFixtures.relation("lanelet", "road")
        val b = OsmFixtures.relation("lanelet", "highway")
        val c = OsmFixtures.relation("lanelet", "walkway")
        val basket = CollectionLogic.Basket<org.openstreetmap.josm.data.osm.Relation>()
        assertTrue(basket.isEmpty())
        assertEquals(listOf(a, b), basket.add(listOf(a, b, a)))
        assertEquals(listOf(a, b), basket.items())
        assertEquals(emptyList<org.openstreetmap.josm.data.osm.Relation>(), basket.add(listOf(a)))
        assertEquals(listOf(c), basket.add(listOf(c)))
        assertEquals(listOf(c), basket.undo())
        assertEquals(listOf(a, b), basket.items())
        assertEquals(listOf(a, b), basket.undo())
        assertTrue(basket.isEmpty())
        assertNull(basket.undo())
    }

    @Test
    fun basketInitialSeedsUndoHistory() {
        val a = OsmFixtures.relation("lanelet", "road")
        val basket = CollectionLogic.Basket(listOf(a))
        assertEquals(1, basket.size())
        assertEquals(listOf(a), basket.undo())
        assertTrue(basket.isEmpty())
    }

    @Test
    fun basketRemoveAndClear() {
        val a = OsmFixtures.relation("lanelet", "road")
        val b = OsmFixtures.relation("lanelet", "highway")
        val basket = CollectionLogic.Basket(listOf(a, b))
        assertTrue(basket.remove(a))
        assertEquals(listOf(b), basket.items())
        assertFalse(basket.remove(a))
        basket.clear()
        assertTrue(basket.isEmpty())
        assertNull(basket.undo())
    }

    @Test
    fun canFinishUsesMinCount() {
        val a = OsmFixtures.relation("lanelet", "road")
        val basket = CollectionLogic.Basket<org.openstreetmap.josm.data.osm.Relation>()
        assertTrue(basket.canFinish(0))
        assertFalse(basket.canFinish(1))
        basket.add(listOf(a))
        assertTrue(basket.canFinish(1))
        assertFalse(basket.canFinish(2))
    }

    @Test
    fun extractLaneletsModeASkipsInferred() {
        val left = OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0)
        val right = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(left, right)
        val ds = OsmFixtures.dataSet(ll)
        assertEquals(emptyList<org.openstreetmap.josm.data.osm.Relation>(), CollectionLogic.extractLaneletsForAdd(ds, listOf(left), modeA = true))
        assertEquals(listOf(ll), CollectionLogic.extractLaneletsForAdd(ds, listOf(left), modeA = false))
        assertEquals(listOf(ll), CollectionLogic.extractLaneletsForAdd(ds, listOf(ll), modeA = true))
    }

    @Test
    fun extractRelationsFiltersTypeAndSubtype() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val keep = OsmFixtures.relation("regulatory_element", "right_of_way", "ref_line" to w)
        val other = OsmFixtures.relation("regulatory_element", "traffic_light", "refers" to w)
        val ds = OsmFixtures.dataSet(keep, other)
        assertEquals(
            listOf(keep),
            CollectionLogic.extractRelationsForAdd(ds, listOf(w), "regulatory_element", "right_of_way", modeA = false),
        )
        assertEquals(
            emptyList<org.openstreetmap.josm.data.osm.Relation>(),
            CollectionLogic.extractRelationsForAdd(ds, listOf(w), "regulatory_element", "right_of_way", modeA = true),
        )
        assertEquals(
            listOf(keep),
            CollectionLogic.extractRelationsForAdd(ds, listOf(keep), "regulatory_element", "right_of_way", modeA = true),
        )
    }

    @Test
    fun relationMembersDedupAndSelectTargets() {
        val w = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        val ll = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val rel = OsmFixtures.relation("regulatory_element", "right_of_way", "ref_line" to w, "yield" to ll)
        OsmFixtures.dataSet(rel, ll)
        val members = CollectionLogic.relationMembers(rel)
        assertEquals(2, members.size)
        assertTrue(w in members)
        assertTrue(ll in members)
        val fromList = CollectionLogic.relationMembersFromList(listOf(rel, rel))
        assertEquals(members, fromList)
        assertEquals(listOf(rel), CollectionLogic.primitivesToSelect(listOf(rel), selectMembers = false))
        assertEquals(members, CollectionLogic.primitivesToSelect(listOf(rel), selectMembers = true))
        assertEquals(listOf(rel), CollectionLogic.primitivesToSelect(listOf(rel), selectMembers = false, single = rel))
        assertEquals(members, CollectionLogic.primitivesToSelect(listOf(rel), selectMembers = true, single = rel))
        val emptyRel = OsmFixtures.relation("regulatory_element", "right_of_way")
        assertEquals(
            listOf(emptyRel),
            CollectionLogic.primitivesToSelect(listOf(emptyRel), selectMembers = true, single = emptyRel),
        )
    }

    @Test
    fun helpBodyAppendsGithubLinks() {
        val body = CollectionLogic.helpBody(
            "Hello",
            listOf("RegulatoryElementTagging" to "RegulatoryElementTagging.md"),
        )
        assertTrue(body.startsWith("Hello"))
        assertTrue(body.contains("--- Documentation ---"))
        assertTrue(body.contains("RegulatoryElementTagging"))
        assertTrue(body.contains(CollectionLogic.GITHUB_BASE + "RegulatoryElementTagging.md"))
        assertEquals("plain", CollectionLogic.helpBody("plain", null))
        assertEquals("plain", CollectionLogic.helpBody("plain", emptyList()))
    }

    @Test
    fun styledMessageHighlightsAndBreaksLines() {
        val html = CollectionLogic.styledMessageHtml(
            "Select linestrings (lane boundaries) or lanelets.",
            "linestrings",
        )
        assertTrue(html.startsWith("<html>"))
        assertTrue(html.contains("<b><font size='+1'>linestrings</font></b>"))
        val multi = CollectionLogic.styledMessageHtml("a\nb", "x")
        assertTrue(multi.contains("<br>"))
    }

    @Test
    fun multiStepLabelAndCount() {
        assertEquals("<html>hi</html>", CollectionLogic.multiStepLabelHtml("<html>hi", 0))
        assertEquals("<html>hi<br>(Added 2 so far.)</html>", CollectionLogic.multiStepLabelHtml("<html>hi", 2))
        assertEquals("Collected: 3 lanelet(s)", CollectionLogic.countLabel(3, CollectionLogic.KIND_LANELET))
        assertEquals(
            "Select at least 1 lanelet or linestring to add.",
            CollectionLogic.emptyExtractMessage(true),
        )
        assertEquals("Need at least 2 relation(s).", CollectionLogic.needMinMessage(2, CollectionLogic.KIND_RELATION))
        assertEquals("No lanelet(s) collected yet.", CollectionLogic.nothingCollectedMessage(CollectionLogic.KIND_LANELET))
    }

    @Test
    fun positionUpperLeftOffsetsThenClamps() {
        val mid = CollectionLogic.positionUpperLeft(0, 0, 1000, 800, 200, 150)
        assertEquals(60, mid.x)
        assertEquals(72, mid.y)
        val overflow = CollectionLogic.positionUpperLeft(10, 20, 300, 200, 280, 180)
        assertEquals(20, overflow.x)
        assertEquals(30, overflow.y)
        val tiny = CollectionLogic.positionUpperLeft(0, 0, 100, 80, 200, 150)
        assertEquals(10, tiny.x)
        assertEquals(10, tiny.y)
    }

    @Test
    fun settingsGateRequiresOptInAndDisplay() {
        assertFalse(CollectionLogic.shouldOpenCollectionDialog(enabled = false, headless = false))
        assertFalse(CollectionLogic.shouldOpenCollectionDialog(enabled = true, headless = true))
        assertTrue(CollectionLogic.shouldOpenCollectionDialog(enabled = true, headless = false))
        assertFalse(LaneletSettings.isCollectionDialogEnabled())
        assertFalse(CollectionLogic.shouldOpenCollectionDialog())
        LaneletSettings.setCollectionDialogEnabled(true)
        assertEquals(Dialogs.isHeadless(), !CollectionLogic.shouldOpenCollectionDialog())
        LaneletSettings.put(LaneletSettings.KEY_COLLECTION_DIALOG, "true")
        assertFalse(LaneletSettings.isCollectionDialogEnabled())
    }

    /**
     * The opt-in governs the actions that can fall back to the current
     * selection. The right-of-way actions cannot: the Jython always opens the
     * collector, and creating the element needs the right_of_way and yield
     * groups kept apart. Gating them behind the (off by default) checkbox left
     * the feature inert until the user found the setting.
     */
    @Test
    fun rightOfWayNeedsTheCollectorRegardlessOfTheOptIn() {
        assertFalse(LaneletSettings.isCollectionDialogEnabled(), "opt-in stays off by default")
        assertTrue(CollectionLogic.collectionDialogRequired(headless = false))
        assertFalse(
            CollectionLogic.collectionDialogRequired(headless = true),
            "only a missing display may skip the collector",
        )
    }

    @Test
    fun wizardPagingStillNextPrevDone() {
        assertEquals(1, DebugRightOfWayWizard.nextIndex(0, 3))
        assertNull(DebugRightOfWayWizard.nextIndex(2, 3))
        assertNull(DebugRightOfWayWizard.prevIndex(0))
        assertEquals(0, DebugRightOfWayWizard.prevIndex(1))
    }

    @Test
    fun rightOfWayCreatedMessageMatchesJython() {
        assertEquals(
            "Created right of way regulatory element.\n" +
                "Yield: 2, Right of way: 1, ref_line: yes\n\n" +
                "Review in Properties dialog (Alt+O if not visible).",
            CreateRightOfWayRelation.createdMessage(2, 1, true),
        )
        assertEquals(
            "Created right of way regulatory element.\n" +
                "Yield: 1, Right of way: 1\n\n" +
                "Review in Properties dialog (Alt+O if not visible).",
            CreateRightOfWayRelation.createdMessage(1, 1, false),
        )
    }

    @Test
    fun primIdAndTags() {
        val rel = OsmFixtures.relation("lanelet", "road")
        OsmFixtures.dataSet(rel)
        assertEquals(rel.uniqueId.toString(), CollectionLogic.primIdStr(rel))
        assertEquals("?", CollectionLogic.primIdStr(null))
        assertEquals("road", CollectionLogic.tagOrDash(rel, "subtype"))
        assertEquals("-", CollectionLogic.tagOrDash(rel, "missing"))
    }

    @Test
    fun showHelpIsNoopWhenHeadless() {
        assumeFalse(!Dialogs.isHeadless())
        CollectionDialog.showHelp("t", "body", CollectionLogic.DEFAULT_LANELET_HELP_LINKS)
        CollectionDialog.showLaneletCollection(
            OsmFixtures.dataSet(),
            onDone = { error("must not finish") },
        )
        CollectionDialog.showRelationCollection(
            OsmFixtures.dataSet(),
            onDone = { error("must not finish") },
            relType = "lanelet",
        )
        CollectionDialog.showStep("t", "m", onOk = { error("must not ok") })
    }

    @Test
    fun startWizardEmptyIsInfoNotCrash() {
        val ds = OsmFixtures.dataSet()
        val ui = org.openstreetmap.josm.plugins.lanelet2.sidecar.RecordingPrompts()
        DebugRightOfWayWizard.startWizard(ds, emptyList(), ui)
        assertEquals(1, ui.infos.size)
        assertTrue(ui.infos[0].first.contains("No right_of_way"))
    }

    @Test
    fun startWizardHeadlessSelectsFirstPage() {
        val yieldLl = OsmFixtures.laneletRelation(
            OsmFixtures.way(0.0 to 1.0, 1.0 to 1.0),
            OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0),
        )
        val stop = OsmFixtures.way(0.0 to 0.0, 1.0 to 0.0)
        stop.put("type", "stop_line")
        val rel = OsmFixtures.relation("regulatory_element", "right_of_way")
        rel.addMember(RelationMember("yield", yieldLl))
        rel.addMember(RelationMember("ref_line", stop))
        val ds = OsmFixtures.dataSet(rel, yieldLl)
        val ui = org.openstreetmap.josm.plugins.lanelet2.sidecar.RecordingPrompts()
        DebugRightOfWayWizard.startWizard(ds, listOf(rel), ui)
        if (Dialogs.isHeadless()) {
            assertTrue(ui.infos.any { it.first.contains("wizard skipped") })
            assertTrue(ds.selected.contains(yieldLl) || ds.selected.contains(stop))
        }
    }
}
