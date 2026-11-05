package tachiyomi.domain.category.model

import java.io.Serializable

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    /** The library's own "Completed" tab: made on the fly, never stored. */
    val isCompletedCategory: Boolean = id == COMPLETED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
        const val COMPLETED_ID = -2L
    }
}
