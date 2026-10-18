package dev.otherworld.budget.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.otherworld.budget.domain.model.Category
import dev.otherworld.budget.ui.theme.BudgetReceiptsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The category picker with two subcategories of the same name under different parents (#1):
 * each must be told apart in the open menu, and the closed field must say which was picked.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDropdownTest {

    @get:Rule val composeRule = createComposeRule()

    private val categories = listOf(
        Category(1, "Food", null),
        Category(2, "Travel", null),
        Category(3, "Other", 1),
        Category(4, "Other", 2),
    )

    @Test fun same_named_subcategories_can_be_told_apart() {
        composeRule.setContent {
            BudgetReceiptsTheme {
                var selected by remember { mutableStateOf<Long?>(null) }
                CategoryDropdown(categories, selected, onSelected = { selected = it })
            }
        }

        composeRule.onNodeWithText("None").performClick()
        composeRule.onAllNodesWithContentDescription("Food › Other").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Travel › Other").assertCountEquals(1)

        composeRule.onNodeWithContentDescription("Travel › Other").performClick()
        composeRule.onNodeWithText("Travel › Other").assertExists()
    }
}
