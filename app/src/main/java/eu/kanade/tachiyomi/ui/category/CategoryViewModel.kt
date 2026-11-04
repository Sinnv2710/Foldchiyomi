package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CategoryViewModel(
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val deleteCategory: DeleteCategory,
    private val reorderCategory: ReorderCategory,
    private val renameCategory: RenameCategory,
    private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    private val dialog = MutableStateFlow<CategoryDialog?>(null)

    val state: StateFlow<CategoryScreenState> = combine(
        getCategories.subscribe(),
        dialog,
        libraryPreferences.hiddenCategories.changes(),
        libraryPreferences.showHiddenCategories.changes(),
    ) { categories, dialog, hidden, showHidden ->
        CategoryScreenState.Success(
            categories = categories.filterNot(Category::isSystemCategory),
            dialog = dialog,
            hiddenIds = hidden.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() },
            showHidden = showHidden,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), CategoryScreenState.Loading)

    fun toggleHidden(category: Category) {
        val id = category.id.toString()
        val hidden = libraryPreferences.hiddenCategories
        hidden.set(hidden.get().let { if (id in it) it - id else it + id })
    }

    fun toggleShowHidden() {
        libraryPreferences.showHiddenCategories.let { it.set(!it.get()) }
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            when (createCategoryWithName.await(name)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        viewModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun renameCategory(category: Category, name: String) {
        viewModelScope.launch {
            when (renameCategory.await(category, name)) {
                is RenameCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun showDialog(dialog: CategoryDialog) {
        this.dialog.update { dialog }
    }

    fun dismissDialog() {
        dialog.update { null }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: List<Category>,
        val dialog: CategoryDialog? = null,
        val hiddenIds: Set<Long> = emptySet(),
        val showHidden: Boolean = false,
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}
