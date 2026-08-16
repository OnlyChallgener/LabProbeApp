package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LabProbeDdnsUiContractTest {
    @Test
    fun listMenuContainsEditDynamicToggleAndDelete() {
        assertEquals(listOf("编辑", "停用", "删除"), labProbeDdnsMenuLabels(true))
        assertEquals(listOf("编辑", "启用", "删除"), labProbeDdnsMenuLabels(false))
    }
}
