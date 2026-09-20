package com.watchpicture.app.ui.viewmodel

import org.junit.Assert.assertNotNull
import org.junit.Test

class ViewModelInstantiationTest {

    @Test
    fun testPackViewModelHasNoArgConstructor() {
        val constructor = PackViewModel::class.java.getConstructor()
        assertNotNull("PackViewModel must provide a public no-arg constructor", constructor)
    }

    @Test
    fun testViewerViewModelHasNoArgConstructor() {
        val constructor = ViewerViewModel::class.java.getConstructor()
        assertNotNull("ViewerViewModel must provide a public no-arg constructor", constructor)
    }
}
