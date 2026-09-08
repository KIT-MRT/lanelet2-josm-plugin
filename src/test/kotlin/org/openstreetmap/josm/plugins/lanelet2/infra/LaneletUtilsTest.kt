package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.openstreetmap.josm.data.UndoRedoHandler

class LaneletUtilsTest {

    @Test
    fun getUndoReturnsSingleton() {
        assertSame(UndoRedoHandler.getInstance(), LaneletUtils.getUndo())
    }

    @Test
    fun focusInTextComponentIsFalseHeadless() {
        assertFalse(LaneletUtils.focusInTextComponent())
    }
}
