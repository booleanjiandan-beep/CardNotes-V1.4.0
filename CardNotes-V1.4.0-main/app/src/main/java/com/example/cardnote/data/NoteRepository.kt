package com.example.cardnote.data

import com.example.cardnote.ui.FilterState
import kotlinx.coroutines.flow.Flow

class NoteRepository(private val dao: NoteDao) {

    fun getAllNotes()          = dao.getAllNotes()
    fun getDownloadedNotes()   = dao.getDownloadedNotes()
    fun getNotDownloadedNotes()= dao.getNotDownloadedNotes()

    /** 根据分类 + 下载筛选 + 搜索词，返回对应 Flow */
    fun queryNotes(
        categoryIds: List<Long>?,   // ← 改为列表
        filter: FilterState,
        search: String
    ): Flow<List<NoteEntity>> {
        val q = search.trim()
        return when {
            q.isNotBlank() && categoryIds != null -> when {
                filter.showAll        -> dao.searchByCategoryIds(q, categoryIds)
                filter.showDownloaded -> dao.searchByCategoryIdsAndDownload(q, categoryIds, true)
                else                  -> dao.searchByCategoryIdsAndDownload(q, categoryIds, false)
            }
            q.isNotBlank() -> when {
                filter.showAll        -> dao.searchAllNotes(q)
                filter.showDownloaded -> dao.searchDownloadedNotes(q)
                else                  -> dao.searchNotDownloadedNotes(q)
            }
            categoryIds != null -> when {
                filter.showAll        -> dao.getNotesByCategoryIds(categoryIds)
                filter.showDownloaded -> dao.getDownloadedByCategoryIds(categoryIds)
                else                  -> dao.getNotDownloadedByCategoryIds(categoryIds)
            }
            else -> when {
                filter.showAll        -> dao.getAllNotes()
                filter.showDownloaded -> dao.getDownloadedNotes()
                else                  -> dao.getNotDownloadedNotes()
            }
        }
    }

    
    // fun queryNotes(
    //     categoryId: Long?,        // null = 全部分类
    //     filter: FilterState,
    //     search: String
    // ): Flow<List<NoteEntity>> {
    //     val q = search.trim()
    //     return when {
    //         // ── 有搜索词 ──
    //         q.isNotBlank() && categoryId != null -> when {
    //             filter.showAll        -> dao.searchByCategory(q, categoryId)
    //             filter.showDownloaded -> dao.searchByCategoryAndDownload(q, categoryId, true)
    //             else                  -> dao.searchByCategoryAndDownload(q, categoryId, false)
    //         }
    //         q.isNotBlank() -> when {
    //             filter.showAll        -> dao.searchAllNotes(q)
    //             filter.showDownloaded -> dao.searchDownloadedNotes(q)
    //             else                  -> dao.searchNotDownloadedNotes(q)
    //         }
    //         // ── 无搜索词，按分类 ──
    //         categoryId != null -> when {
    //             filter.showAll        -> dao.getNotesByCategory(categoryId)
    //             filter.showDownloaded -> dao.getDownloadedByCategory(categoryId)
    //             else                  -> dao.getNotDownloadedByCategory(categoryId)
    //         }
    //         // ── 无搜索词，无分类筛选 ──
    //         else -> when {
    //             filter.showAll        -> dao.getAllNotes()
    //             filter.showDownloaded -> dao.getDownloadedNotes()
    //             else                  -> dao.getNotDownloadedNotes()
    //         }
    //     }
    // }

    suspend fun insertNote(note: NoteEntity)  = dao.insertNote(note)
    suspend fun updateNote(note: NoteEntity)  = dao.updateNote(note)
    suspend fun deleteNote(note: NoteEntity)  = dao.deleteNote(note)
    suspend fun updateDownloadStatus(id: Long, dl: Boolean) = dao.updateDownloadStatus(id, dl)
    suspend fun getAllNotesSnapshot() = dao.getAllNotesSnapshot()
    suspend fun getNoteById(id: Long) = dao.getNoteById(id)
}

class CategoryRepository(private val dao: CategoryDao) {
    fun getAllCategories() = dao.getAllCategories()
    suspend fun getAllCategoriesSnapshot() = dao.getAllCategoriesSnapshot()
    fun getRootCategories()  = dao.getRootCategories()
    fun getChildren(pid: Long) = dao.getChildren(pid)
    suspend fun getDepth(id: Long): Int {
        var depth = 0
        var cur: Long? = id
        while (cur != null) { cur = dao.getParentId(cur); depth++ }
        return depth
    }
    suspend fun canAddChild(parentId: Long): Boolean {
        val parentDepth = getDepth(parentId)
        return parentDepth < 4   // 最多四级：depth 1/2/3/4
    }
    suspend fun insert(cat: CategoryEntity) = dao.insertCategory(cat)
    suspend fun update(cat: CategoryEntity) = dao.updateCategory(cat)
    suspend fun delete(cat: CategoryEntity) = dao.deleteCategory(cat)

    suspend fun moveCategory(category: CategoryEntity, newParentId: Long?): String? {
        val all = dao.getAllCategoriesSnapshot()
        val validationError = validateMove(category.id, newParentId, all)
        if (validationError != null) return validationError
        dao.updateCategory(category.copy(parentId = newParentId))
        return null
    }

    private fun validateMove(categoryId: Long, newParentId: Long?, all: List<CategoryEntity>): String? {
        if (newParentId == categoryId) return "不能移动到自身"
        if (newParentId != null) {
            if (all.none { it.id == newParentId }) return "目标分类不存在"
            if (isDescendant(categoryId, newParentId, all)) return "不能移动到子分类下"
        }
        val newBaseDepth = treeDepth(newParentId, all) + 1
        val maxRelativeDepth = subtreeMaxRelativeDepth(categoryId, all)
        if (newBaseDepth + maxRelativeDepth > 3) return "移动后分类层级将超过四级"
        return null
    }

    private fun treeDepth(categoryId: Long?, all: List<CategoryEntity>): Int {
        if (categoryId == null) return -1
        var depth = 0
        var currentId: Long? = categoryId
        while (currentId != null) {
            val entity = all.firstOrNull { it.id == currentId } ?: break
            if (entity.parentId == null) return depth
            depth++
            currentId = entity.parentId
        }
        return depth
    }

    private fun subtreeMaxRelativeDepth(categoryId: Long, all: List<CategoryEntity>): Int {
        val children = all.filter { it.parentId == categoryId }
        if (children.isEmpty()) return 0
        return 1 + children.maxOf { subtreeMaxRelativeDepth(it.id, all) }
    }

    private fun isDescendant(ancestorId: Long, candidateId: Long, all: List<CategoryEntity>): Boolean {
        var currentId: Long? = candidateId
        while (currentId != null) {
            if (currentId == ancestorId) return true
            currentId = all.firstOrNull { it.id == currentId }?.parentId
        }
        return false
    }
}
