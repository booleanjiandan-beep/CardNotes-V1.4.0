package com.example.cardnote.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cardnote.data.*
import com.example.cardnote.util.ImageStorageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

// ── 筛选状态 ──
data class FilterState(
    val showDownloaded: Boolean = true,
    val showNotDownloaded: Boolean = true
) {
    val showAll: Boolean get() = showDownloaded == showNotDownloaded
}

// ── 分类树节点（UI 用） ──
data class CategoryNode(
    val entity: CategoryEntity,
    val children: List<CategoryNode> = emptyList(),
    val depth: Int = 0            // 0=根, 1=二级, 2=三级
)

data class NoteUiState(
    val filteredNotes: List<NoteEntity> = emptyList(),
    val filterState: FilterState = FilterState(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isLoading: Boolean = false,
    val currentPagerIndex: Int = 0,
    // 分类
    val categoryTree: List<CategoryNode> = emptyList(),
    val selectedCategoryId: Long? = null,   // null = 全部
    val isCategoryDrawerOpen: Boolean = false,
    // sheets & dialogs
    val showAddSheet: Boolean = false,
    val noteToEdit: NoteEntity? = null,
    val noteToDelete: NoteEntity? = null,
    val importReviewItems: List<ImportReviewItem> = emptyList(),
    val duplicateNameDialogMessage: String? = null,
    val duplicateNameCanForceSave: Boolean = false,
    val snackbarMessage: String? = null
)

data class ImportReviewItem(
    val draft: NoteImportDraft,
    val conflictType: ConflictType,
    val similarName: String? = null,
    val selected: Boolean = true
)

data class NoteImportDraft(
    val name: String,
    val url: String,
    val isDownloaded: Boolean,
    val remarks: String,
    val categoryName: String?,
    val imageBytesList: List<ByteArray> = emptyList()
)

enum class ConflictType { NONE, EXACT, SIMILAR }

private sealed class PendingSaveAction {
    data class Add(
        val name: String,
        val url: String,
        val isDownloaded: Boolean,
        val remarks: String,
        val imageUris: List<Uri>,
        val categoryId: Long?
    ) : PendingSaveAction()

    data class Edit(
        val note: NoteEntity,
        val name: String,
        val url: String,
        val isDownloaded: Boolean,
        val remarks: String,
        val keptPaths: List<String>,
        val newUris: List<Uri>,
        val categoryId: Long?
    ) : PendingSaveAction()
}

// private data class QueryKey(val catId: Long?, val filter: FilterState, val search: String)
// 改为携带 id 列表
private data class QueryKey(val categoryIds: List<Long>?, val filter: FilterState, val search: String)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NoteViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val SIMILARITY_THRESHOLD = 0.78
        private const val EXPORT_NOTES_JSON = "notes.json"
        private const val EXPORT_ASSETS_DIR = "assets/"
    }

    private val noteRepo: NoteRepository
    private val catRepo:  CategoryRepository
    private val appCtx = application.applicationContext

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    private val _rawSearch = MutableStateFlow("")
    private val _searchQuery = _rawSearch.debounce(300).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()
    private var pendingSaveAction: PendingSaveAction? = null

    private fun normalizeName(value: String): String =
        value.lowercase().replace("\\s+".toRegex(), "")

    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = a.length
        val m = b.length
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..m) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + cost
                )
                prev = temp
            }
        }
        val distance = dp[m]
        return 1.0 - (distance.toDouble() / max(n, m).toDouble())
    }

    private suspend fun findNameConflict(
        name: String,
        excludeNoteId: Long? = null
    ): Pair<ConflictType, String?> {
        val target = normalizeName(name.trim())
        if (target.isBlank()) return ConflictType.NONE to null
        val notes = noteRepo.getAllNotesSnapshot()
        val candidates = if (excludeNoteId == null) notes else notes.filterNot { it.id == excludeNoteId }
        candidates.firstOrNull { normalizeName(it.name) == target }?.let {
            return ConflictType.EXACT to it.name
        }
        var bestName: String? = null
        var bestScore = 0.0
        candidates.forEach { note ->
            val score = levenshteinSimilarity(target, normalizeName(note.name))
            if (score > bestScore) {
                bestScore = score
                bestName = note.name
            }
        }
        return if (bestScore >= SIMILARITY_THRESHOLD) ConflictType.SIMILAR to bestName
        else ConflictType.NONE to null
    }

    init {
        val db = NoteDatabase.getDatabase(application)
        noteRepo = NoteRepository(db.noteDao())
        catRepo  = CategoryRepository(db.categoryDao())

        // 监听分类树
        viewModelScope.launch {
            catRepo.getAllCategories().collect { all ->
                _uiState.update { it.copy(categoryTree = buildTree(all, null, 0)) }
            }
        }

        // 监听笔记（catId + filter + search 三路合并）
        viewModelScope.launch {
            combine(
                _uiState.map { it.selectedCategoryId }.distinctUntilChanged(),
                _filterState,
                _searchQuery
            ) { catId, filter, search ->
                // 直接读 StateFlow 当前值，无需加入 combine
                val tree = _uiState.value.categoryTree
                val ids = if (catId == null) null else collectIds(tree, catId)
                QueryKey(ids, filter, search)
            }
                .flatMapLatest { key ->
                    noteRepo.queryNotes(key.categoryIds, key.filter, key.search)
                }
                .collect { notes ->
                    _uiState.update { state ->
                        state.copy(
                            filteredNotes = notes,
                            currentPagerIndex = if (state.currentPagerIndex >= notes.size) 0
                            else state.currentPagerIndex
                        )
                    }
                }
        }
        
        // viewModelScope.launch {
        //     combine(
        //         _uiState.map { it.selectedCategoryId }.distinctUntilChanged(),
        //         _uiState.map { it.categoryTree }.distinctUntilChanged(),   // ← 新增，需要 tree 才能展开 ids
        //         _filterState,
        //         _searchQuery
        //     ) { catId, filter, search -> QueryKey(catId, filter, search) }
        //         .flatMapLatest { key ->
        //             noteRepo.queryNotes(key.catId, key.filter, key.search)
        //         }
        //         .collect { notes ->
        //             _uiState.update { state ->
        //                 state.copy(filteredNotes = notes,
        //                     currentPagerIndex = if (state.currentPagerIndex >= notes.size) 0 else state.currentPagerIndex)
        //             }
        //         }
        // }

        viewModelScope.launch {
            _searchQuery.collect { q ->
                _uiState.update { it.copy(searchQuery = q, currentPagerIndex = 0) }
            }
        }
    }

    private fun buildTree(all: List<CategoryEntity>, parentId: Long?, depth: Int): List<CategoryNode> {
        if (depth >= 4) return emptyList()
        return all.filter { it.parentId == parentId }.map { cat ->
            CategoryNode(entity = cat, depth = depth,
                children = buildTree(all, cat.id, depth + 1))
        }
    }
    
    /** 收集某分类节点自身 + 所有后代的 id */
    private fun collectIds(nodes: List<CategoryNode>, targetId: Long): List<Long>? {
        for (node in nodes) {
            if (node.entity.id == targetId) {
                // 找到目标节点，收集自身 + 所有后代
                val result = mutableListOf<Long>()
                fun collect(n: CategoryNode) {
                    result.add(n.entity.id)
                    n.children.forEach { collect(it) }
                }
                collect(node)
                return result
            }
            // 递归搜索子树
            collectIds(node.children, targetId)?.let { return it }
        }
        return null
    }

    // ── 分类操作 ──
    fun selectCategory(id: Long?) {
        _uiState.update { it.copy(selectedCategoryId = id, currentPagerIndex = 0) }
    }
    fun toggleCategoryDrawer() {
        _uiState.update { it.copy(isCategoryDrawerOpen = !it.isCategoryDrawerOpen) }
    }
    fun closeCategoryDrawer() {
        _uiState.update { it.copy(isCategoryDrawerOpen = false) }
    }
    fun addCategory(name: String, parentId: Long?, colorHex: String = "#6C63FF") {
        viewModelScope.launch {
            if (parentId != null && !catRepo.canAddChild(parentId)) {
                _uiState.update { it.copy(snackbarMessage = "最多支持四级分类") }
                return@launch
            }
            catRepo.insert(CategoryEntity(name = name.trim(), parentId = parentId, colorHex = colorHex))
        }
    }
    fun renameCategory(cat: CategoryEntity, newName: String, newColor: String = cat.colorHex) {
        viewModelScope.launch { catRepo.update(cat.copy(name = newName.trim(), colorHex = newColor)) }
    }
    // fun deleteCategory(cat: CategoryEntity) {
    //     viewModelScope.launch {
    //         catRepo.delete(cat)
    //         if (_uiState.value.selectedCategoryId == cat.id)
    //             _uiState.update { it.copy(selectedCategoryId = null) }
    //     }
    // }
        fun deleteCategory(cat: CategoryEntity) {
        viewModelScope.launch {
            val tree = _uiState.value.categoryTree
            val categoryIds = collectIds(tree, cat.id) ?: listOf(cat.id)

            val allFilter = FilterState(showDownloaded = true, showNotDownloaded = true)
            val notesToDelete = noteRepo.queryNotes(categoryIds, allFilter, "").first()

            notesToDelete.forEach { note ->
                ImageStorageManager.deleteImages(note.images)
                noteRepo.deleteNote(note)
            }

            catRepo.delete(cat)

            if (_uiState.value.selectedCategoryId == cat.id) {
                _uiState.update { it.copy(selectedCategoryId = null) }
            }
            // 不再显示任何 Snackbar
        }
    }
    
    // ── 搜索 ──
    fun onSearchQueryChange(query: String) { _rawSearch.value = query; _uiState.update { it.copy(searchQuery = query) } }
    fun toggleSearch() { val a = !_uiState.value.isSearchActive; _uiState.update { it.copy(isSearchActive = a) }; if (!a) clearSearch() }
    fun clearSearch() { _rawSearch.value = ""; _uiState.update { it.copy(searchQuery = "", isSearchActive = false, currentPagerIndex = 0) } }

    // ── 筛选 ──
    fun toggleDownloadedFilter()    { _filterState.update { it.copy(showDownloaded    = !it.showDownloaded) };    _uiState.update { it.copy(currentPagerIndex = 0) } }
    fun toggleNotDownloadedFilter() { _filterState.update { it.copy(showNotDownloaded = !it.showNotDownloaded) }; _uiState.update { it.copy(currentPagerIndex = 0) } }

    // ── Pager ──
    fun onPagerPageChanged(index: Int) { _uiState.update { it.copy(currentPagerIndex = index) } }

    // ── 新增 Sheet ──
    fun showAddSheet()  { _uiState.update { it.copy(showAddSheet = true) } }
    fun hideAddSheet()  { _uiState.update { it.copy(showAddSheet = false) } }

    // fun addNote(name: String, url: String, isDownloaded: Boolean, remarks: String,
    //             imageUris: List<Uri>, categoryId: Long?) {
    //     viewModelScope.launch {
    //         _uiState.update { it.copy(isLoading = true) }
    //         try {
    //             val paths = ImageStorageManager.copyAllToPrivateStorage(appCtx, imageUris)
    //             noteRepo.insertNote(NoteEntity(name = name.trim(), url = url.trim(),
    //                 isDownloaded = isDownloaded, remarks = remarks.trim(),
    //                 images = paths, categoryId = categoryId))
    //             _uiState.update { it.copy(showAddSheet = false, snackbarMessage = "笔记已添加") }
    //         } catch (e: Exception) {
    //             _uiState.update { it.copy(snackbarMessage = "添加失败：${e.message}") }
    //         } finally { _uiState.update { it.copy(isLoading = false) } }
    //     }
    // }
    fun addNote(
        name: String,
        url: String,
        isDownloaded: Boolean,
        remarks: String,
        imageUris: List<Uri>,
        categoryId: Long?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val (conflictType, matched) = findNameConflict(name.trim())
                when (conflictType) {
                    ConflictType.EXACT -> {
                        pendingSaveAction = null
                        _uiState.update {
                            it.copy(
                                duplicateNameDialogMessage = "已存在同名笔记：$matched",
                                duplicateNameCanForceSave = false
                            )
                        }
                        return@launch
                    }
                    ConflictType.SIMILAR -> {
                        pendingSaveAction = PendingSaveAction.Add(
                            name = name,
                            url = url,
                            isDownloaded = isDownloaded,
                            remarks = remarks,
                            imageUris = imageUris,
                            categoryId = categoryId
                        )
                        _uiState.update {
                            it.copy(
                                duplicateNameDialogMessage = "发现相似笔记：$matched\n仍要保存吗？",
                                duplicateNameCanForceSave = true
                            )
                        }
                        return@launch
                    }
                    ConflictType.NONE -> Unit
                }
                val paths = ImageStorageManager.copyAllToPrivateStorage(appCtx, imageUris)
                noteRepo.insertNote(NoteEntity(name = name.trim(), url = url.trim(),
                    isDownloaded = isDownloaded, remarks = remarks.trim(),
                    images = paths, categoryId = categoryId))
                _uiState.update { it.copy(showAddSheet = false) }   // ← 只关闭弹窗，不显示 Snackbar
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "添加失败：${e.message}") }
            } finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }
    // ── 编辑 Sheet ──
    fun showEditSheet(note: NoteEntity) { _uiState.update { it.copy(noteToEdit = note) } }
    fun hideEditSheet()                  { _uiState.update { it.copy(noteToEdit = null) } }

    fun saveEdit(
        note: NoteEntity,
        name: String,
        url: String,
        isDownloaded: Boolean,
        remarks: String,
        keptPaths: List<String>,
        newUris: List<Uri>,
        categoryId: Long?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val (conflictType, matched) = findNameConflict(name, excludeNoteId = note.id)
                when (conflictType) {
                    ConflictType.EXACT -> {
                        pendingSaveAction = null
                        _uiState.update {
                            it.copy(
                                duplicateNameDialogMessage = "已存在同名笔记：$matched",
                                duplicateNameCanForceSave = false
                            )
                        }
                        return@launch
                    }
                    ConflictType.SIMILAR -> {
                        pendingSaveAction = PendingSaveAction.Edit(
                            note = note,
                            name = name,
                            url = url,
                            isDownloaded = isDownloaded,
                            remarks = remarks,
                            keptPaths = keptPaths,
                            newUris = newUris,
                            categoryId = categoryId
                        )
                        _uiState.update {
                            it.copy(
                                duplicateNameDialogMessage = "发现相似笔记：$matched\n仍要保存吗？",
                                duplicateNameCanForceSave = true
                            )
                        }
                        return@launch
                    }
                    ConflictType.NONE -> Unit
                }
                val removed = note.images - keptPaths.toSet()
                ImageStorageManager.deleteImages(removed)
                val newPaths = ImageStorageManager.copyAllToPrivateStorage(appCtx, newUris)
                noteRepo.updateNote(note.copy(name = name.trim(), url = url.trim(),
                    isDownloaded = isDownloaded, remarks = remarks.trim(),
                    images = (keptPaths + newPaths).take(9), categoryId = categoryId))
                _uiState.update { it.copy(noteToEdit = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "保存失败：${e.message}") }
            } finally { _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun confirmForceSaveDuplicateName() {
        val action = pendingSaveAction ?: return
        clearDuplicateNameDialog()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                when (action) {
                    is PendingSaveAction.Add -> {
                        val paths = ImageStorageManager.copyAllToPrivateStorage(appCtx, action.imageUris)
                        noteRepo.insertNote(
                            NoteEntity(
                                name = action.name.trim(),
                                url = action.url.trim(),
                                isDownloaded = action.isDownloaded,
                                remarks = action.remarks.trim(),
                                images = paths,
                                categoryId = action.categoryId
                            )
                        )
                        _uiState.update { it.copy(showAddSheet = false) }
                    }
                    is PendingSaveAction.Edit -> {
                        val removed = action.note.images - action.keptPaths.toSet()
                        ImageStorageManager.deleteImages(removed)
                        val newPaths = ImageStorageManager.copyAllToPrivateStorage(appCtx, action.newUris)
                        noteRepo.updateNote(
                            action.note.copy(
                                name = action.name.trim(),
                                url = action.url.trim(),
                                isDownloaded = action.isDownloaded,
                                remarks = action.remarks.trim(),
                                images = (action.keptPaths + newPaths).take(9),
                                categoryId = action.categoryId
                            )
                        )
                        _uiState.update { it.copy(noteToEdit = null) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "保存失败：${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── 删除 ──
    fun requestDelete(note: NoteEntity) { _uiState.update { it.copy(noteToDelete = note) } }
    fun cancelDelete()  { _uiState.update { it.copy(noteToDelete = null) } }
    fun confirmDelete() {
        val note = _uiState.value.noteToDelete ?: return
        viewModelScope.launch {
            ImageStorageManager.deleteImages(note.images)
            noteRepo.deleteNote(note)
            _uiState.update { it.copy(noteToDelete = null) }
        }
    }

    fun toggleDownloadStatus(note: NoteEntity) {
        viewModelScope.launch { noteRepo.updateDownloadStatus(note.id, !note.isDownloaded) }
    }

    fun removeImageFromNote(note: NoteEntity, path: String) {
        viewModelScope.launch {
            ImageStorageManager.deleteImage(path)
            noteRepo.updateNote(note.copy(images = note.images - path))
        }
    }

    fun exportNotes(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val allNotes = noteRepo.getAllNotesSnapshot()
                val categories = _uiState.value.categoryTree
                val categoryNameById = mutableMapOf<Long, String>()
                fun walk(nodes: List<CategoryNode>) {
                    nodes.forEach { node ->
                        categoryNameById[node.entity.id] = node.entity.name
                        walk(node.children)
                    }
                }
                walk(categories)

                val notesArray = JSONArray()
                val exportedImagePathMap = mutableMapOf<String, String>()
                allNotes.forEach { note ->
                    val exportedImages = buildList {
                        for (path in note.images) {
                            val existing = exportedImagePathMap[path]
                            if (existing != null) {
                                add(existing)
                                continue
                            }
                            val sourceFile = File(path)
                            if (!sourceFile.exists() || !sourceFile.isFile) continue
                            val extension = sourceFile.extension.takeIf { it.isNotBlank() } ?: "jpg"
                            val entryName = "${EXPORT_ASSETS_DIR}${UUID.randomUUID()}.$extension"
                            exportedImagePathMap[path] = entryName
                            add(entryName)
                        }
                    }
                    notesArray.put(
                        JSONObject().apply {
                            put("name", note.name)
                            put("url", note.url)
                            put("isDownloaded", note.isDownloaded)
                            put("remarks", note.remarks)
                            put("categoryName", note.categoryId?.let { categoryNameById[it] })
                            put("images", JSONArray(exportedImages))
                        }
                    )
                }
                val payload = JSONObject().apply {
                    put("schemaVersion", 2)
                    put("exportedAt", System.currentTimeMillis())
                    put("notes", notesArray)
                }
                appCtx.contentResolver.openOutputStream(uri)?.use { outStream ->
                    ZipOutputStream(outStream).use { zipOut ->
                        zipOut.putNextEntry(ZipEntry(EXPORT_NOTES_JSON))
                        zipOut.write(payload.toString(2).toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        exportedImagePathMap.forEach { (sourcePath, entryName) ->
                            val file = File(sourcePath)
                            if (!file.exists() || !file.isFile) return@forEach
                            zipOut.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    }
                } ?: error("无法写入导出文件")
            }.onSuccess {
                _uiState.update { it.copy(snackbarMessage = "导出成功") }
            }.onFailure { e ->
                _uiState.update { it.copy(snackbarMessage = "导出失败：${e.message}") }
            }
        }
    }

    fun parseImportFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val importPayload = appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    readImportPayloadFromZip(input)
                } ?: error("无法读取导入文件")
                val notes = importPayload.root.optJSONArray("notes") ?: JSONArray()
                val existed = noteRepo.getAllNotesSnapshot()
                val importItems = mutableListOf<ImportReviewItem>()
                for (i in 0 until notes.length()) {
                    val obj = notes.optJSONObject(i) ?: continue
                    val draft = NoteImportDraft(
                        name = obj.optString("name", "").trim(),
                        url = obj.optString("url", "").trim(),
                        isDownloaded = obj.optBoolean("isDownloaded", false),
                        remarks = obj.optString("remarks", "").trim(),
                        categoryName = obj.optString("categoryName", "").trim().ifBlank { null },
                        imageBytesList = buildList {
                            val arr = obj.optJSONArray("images") ?: JSONArray()
                            for (j in 0 until arr.length()) {
                                val entryName = arr.optString(j).trim()
                                if (entryName.isNotEmpty()) {
                                    importPayload.assets[entryName]?.let { add(it) }
                                }
                            }
                        }
                    )
                    if (draft.name.isBlank()) continue
                    val target = normalizeName(draft.name)
                    val exact = existed.firstOrNull { normalizeName(it.name) == target }
                    if (exact != null) {
                        importItems += ImportReviewItem(
                            draft = draft,
                            conflictType = ConflictType.EXACT,
                            similarName = exact.name,
                            selected = false
                        )
                        continue
                    }
                    var best: String? = null
                    var score = 0.0
                    existed.forEach { n ->
                        val cur = levenshteinSimilarity(target, normalizeName(n.name))
                        if (cur > score) {
                            score = cur
                            best = n.name
                        }
                    }
                    if (score >= SIMILARITY_THRESHOLD) {
                        importItems += ImportReviewItem(
                            draft = draft,
                            conflictType = ConflictType.SIMILAR,
                            similarName = best,
                            selected = false
                        )
                    } else {
                        importItems += ImportReviewItem(
                            draft = draft,
                            conflictType = ConflictType.NONE,
                            selected = true
                        )
                    }
                }
                importItems
            }.onSuccess { items ->
                _uiState.update { it.copy(importReviewItems = items) }
            }.onFailure { e ->
                _uiState.update { it.copy(snackbarMessage = "导入解析失败：${e.message}") }
            }
        }
    }

    fun toggleImportItem(index: Int, selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                importReviewItems = state.importReviewItems.mapIndexed { i, item ->
                    if (i == index) item.copy(selected = selected) else item
                }
            )
        }
    }

    fun closeImportReview() {
        _uiState.update { it.copy(importReviewItems = emptyList()) }
    }

    fun confirmImportSelected() {
        viewModelScope.launch {
            val items = _uiState.value.importReviewItems.filter { it.selected }
            if (items.isEmpty()) {
                _uiState.update { it.copy(importReviewItems = emptyList(), snackbarMessage = "没有可导入的笔记") }
                return@launch
            }
            var count = 0
            val categoryMap = mutableMapOf<String, Long>()
            val allCategories = _uiState.value.categoryTree
            fun walk(nodes: List<CategoryNode>) {
                nodes.forEach { node ->
                    categoryMap.putIfAbsent(node.entity.name.trim(), node.entity.id)
                    walk(node.children)
                }
            }
            walk(allCategories)

            items.forEach { item ->
                val categoryId = item.draft.categoryName?.let { name ->
                    val key = name.trim()
                    categoryMap[key] ?: catRepo.insert(CategoryEntity(name = key)).also { categoryMap[key] = it }
                }
                val binaryImportedPaths = writeRawImagesToPrivateStorage(
                    context = appCtx,
                    imageBytesList = item.draft.imageBytesList
                )
                val finalImagePaths = binaryImportedPaths
                val existing = noteRepo.getAllNotesSnapshot().firstOrNull {
                    normalizeName(it.name) == normalizeName(item.draft.name)
                }
                if (existing != null && item.conflictType == ConflictType.EXACT) {
                    ImageStorageManager.deleteImages(existing.images)
                    noteRepo.updateNote(
                        existing.copy(
                            name = item.draft.name,
                            url = item.draft.url,
                            isDownloaded = item.draft.isDownloaded,
                            remarks = item.draft.remarks,
                            images = finalImagePaths,
                            categoryId = categoryId
                        )
                    )
                } else {
                    noteRepo.insertNote(
                        NoteEntity(
                            name = item.draft.name,
                            url = item.draft.url,
                            isDownloaded = item.draft.isDownloaded,
                            remarks = item.draft.remarks,
                            images = finalImagePaths,
                            categoryId = categoryId
                        )
                    )
                }
                count++
            }
            _uiState.update { it.copy(importReviewItems = emptyList(), snackbarMessage = "已导入 $count 条笔记") }
        }
    }

    fun clearDuplicateNameDialog() {
        pendingSaveAction = null
        _uiState.update { it.copy(duplicateNameDialogMessage = null, duplicateNameCanForceSave = false) }
    }

    fun clearSnackbar() { _uiState.update { it.copy(snackbarMessage = null) } }

    private data class ImportPayload(
        val root: JSONObject,
        val assets: Map<String, ByteArray>
    )

    private suspend fun readImportPayloadFromZip(inputStream: java.io.InputStream): ImportPayload =
        withContext(Dispatchers.IO) {
            ZipInputStream(inputStream).use { zipIn ->
                val assets = mutableMapOf<String, ByteArray>()
                var notesJsonText: String? = null
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bytes = zipIn.readBytes()
                        if (entry.name == EXPORT_NOTES_JSON) {
                            notesJsonText = bytes.toString(Charsets.UTF_8)
                        } else if (entry.name.startsWith(EXPORT_ASSETS_DIR)) {
                            assets[entry.name] = bytes
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                val root = JSONObject(notesJsonText ?: error("压缩包中缺少 $EXPORT_NOTES_JSON"))
                ImportPayload(root = root, assets = assets)
            }
        }

    private suspend fun writeRawImagesToPrivateStorage(
        context: Context,
        imageBytesList: List<ByteArray>
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "note_images").also { it.mkdirs() }
        imageBytesList.mapNotNull { bytes ->
            runCatching {
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                file.outputStream().use { it.write(bytes) }
                file.absolutePath
            }.getOrNull()
        }
    }
}
