package dev.otherworld.budget.ui.components

import dev.otherworld.budget.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {
    private fun rows(vararg categories: Category) =
        categories.toList().pickerRows().map { Triple(it.category.id, it.depth, it.path) }

    @Test fun `subcategories follow their parent one level deeper`() {
        // Two subcategories with the same name under different parents were indistinguishable
        // in a flat list (issue #1).
        val result = rows(
            Category(1, "Food", null),
            Category(2, "Travel", null),
            Category(3, "Other", 1),
            Category(4, "Other", 2),
        )
        assertEquals(
            listOf(
                Triple(1L, 0, "Food"),
                Triple(3L, 1, "Food › Other"),
                Triple(2L, 0, "Travel"),
                Triple(4L, 1, "Travel › Other"),
            ),
            result,
        )
    }

    @Test fun `deeper subcategories nest further and keep the server's order among siblings`() {
        val result = rows(
            Category(1, "Home", null),
            Category(2, "Bills", 1),
            Category(3, "Energy", 2),
            Category(4, "Repairs", 1),
        )
        assertEquals(
            listOf(
                Triple(1L, 0, "Home"),
                Triple(2L, 1, "Home › Bills"),
                Triple(3L, 2, "Home › Bills › Energy"),
                Triple(4L, 1, "Home › Repairs"),
            ),
            result,
        )
    }

    @Test fun `a subcategory whose parent isn't listed goes at the top level`() {
        assertEquals(listOf(Triple(5L, 0, "Shared")), rows(Category(5, "Shared", 99)))
    }

    @Test fun `categories in a parent loop are still listed, once each`() {
        val result = rows(Category(1, "A", 2), Category(2, "B", 1), Category(3, "C", null))
        assertEquals(listOf(3L, 1L, 2L), result.map { it.first })
    }
}
