package dev.otherworld.budget.ui.components

import dev.otherworld.budget.domain.model.Category

/**
 * One row of the category picker: a category, how deep it sits under its parents (0 = top) and
 * its full name from the top, e.g. "Food › Other".
 */
data class CategoryPickerRow(val category: Category, val depth: Int, val path: String)

/**
 * The categories arranged as the web app's pickers show them: each subcategory straight after its
 * parent, one level deeper, siblings in the server's order. A flat list made two subcategories of
 * the same name under different parents look identical (#1). A category whose parent isn't in the
 * list -- a shared subcategory whose parent wasn't shared -- goes at the top level. Each category
 * appears exactly once, even if the parent links were ever to form a cycle.
 */
fun List<Category>.pickerRows(): List<CategoryPickerRow> {
    val ids = mapTo(HashSet()) { it.id }
    val children = filter { it.parentId in ids }.groupBy { it.parentId }
    val rows = mutableListOf<CategoryPickerRow>()
    val placed = HashSet<Long>()

    fun place(category: Category, depth: Int, parentPath: String?) {
        if (!placed.add(category.id)) return
        val path = if (parentPath == null) category.name else "$parentPath › ${category.name}"
        rows += CategoryPickerRow(category, depth, path)
        children[category.id].orEmpty().forEach { place(it, depth + 1, path) }
    }

    filter { it.parentId !in ids }.forEach { place(it, 0, null) }
    // Only categories caught in a parent cycle are left unplaced; list them rather than drop them.
    forEach { place(it, 0, null) }
    return rows
}
