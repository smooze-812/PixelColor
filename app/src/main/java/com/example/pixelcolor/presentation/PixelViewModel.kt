package com.example.pixelcolor.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelcolor.data.ArtworkEntity
import com.example.pixelcolor.data.PixelRepository
import com.example.pixelcolor.data.Template
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PixelUiState(
    val templates: List<Template> = emptyList(),
    val progressMap: Map<String, Map<String, Int>> = emptyMap(),
    val artworks: List<ArtworkEntity> = emptyList(),
    val selectedTemplateId: String? = null,
    val activeColorNumber: Int = 1,
    val selectedArtwork: ArtworkEntity? = null,
    val statusMessage: String? = null,
    val sessionTimeSeconds: Long = 0L // Добавили время сессии
) {
    val currentTemplate: Template? get() = templates.find { it.id == selectedTemplateId }
    val currentFilledCells: Map<String, Int> get() = selectedTemplateId?.let { progressMap[it] } ?: emptyMap()

    val correctCellsCount: Int get() {
        val tmpl = currentTemplate ?: return 0
        return currentFilledCells.count { (key, appliedColor) ->
            val (r, c) = key.split("_").map { it.toInt() }
            tmpl.grid[r][c] == appliedColor
        }
    }

    val isCurrentCompleted: Boolean get() {
        val tmpl = currentTemplate ?: return false
        return tmpl.totalColorableCells > 0 && correctCellsCount >= tmpl.totalColorableCells
    }

    val completedColors: Set<Int> get() {
        val tmpl = currentTemplate ?: return emptySet()
        val requiredCounts = mutableMapOf<Int, Int>()
        val filledCounts = mutableMapOf<Int, Int>()

        tmpl.grid.forEach { row -> row.forEach { color -> if (color > 0) requiredCounts[color] = requiredCounts.getOrDefault(color, 0) + 1 } }
        currentFilledCells.forEach { (key, appliedColor) ->
            val (r, c) = key.split("_").map { it.toInt() }
            if (tmpl.grid[r][c] == appliedColor) {
                filledCounts[appliedColor] = filledCounts.getOrDefault(appliedColor, 0) + 1
            }
        }
        return requiredCounts.filter { (color, req) -> filledCounts.getOrDefault(color, 0) >= req }.keys
    }
}

class PixelViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PixelRepository(application)
    private val _uiState = MutableStateFlow(PixelUiState())
    val uiState: StateFlow<PixelUiState> = _uiState.asStateFlow()
    private var saveDebounceJob: Job? = null
    private var timerJob: Job? = null // Джоб для таймера

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    templates = repository.loadTemplates(),
                    progressMap = repository.getAllProgressMap(),
                    artworks = repository.getArtworks()
                )
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                if (!_uiState.value.isCurrentCompleted) {
                    _uiState.update { it.copy(sessionTimeSeconds = it.sessionTimeSeconds + 1) }
                }
            }
        }
    }

    fun openTemplate(template: Template) {
        val state = _uiState.value
        val filled = state.progressMap[template.id] ?: emptyMap()

        val requiredCounts = mutableMapOf<Int, Int>()
        val filledCounts = mutableMapOf<Int, Int>()
        template.grid.forEach { row -> row.forEach { color -> if (color > 0) requiredCounts[color] = requiredCounts.getOrDefault(color, 0) + 1 } }
        filled.forEach { (key, appliedColor) ->
            val (r, c) = key.split("_").map { it.toInt() }
            if (template.grid[r][c] == appliedColor) filledCounts[appliedColor] = filledCounts.getOrDefault(appliedColor, 0) + 1
        }
        val finished = requiredCounts.filter { (color, req) -> filledCounts.getOrDefault(color, 0) >= req }.keys

        val firstAvailableColor = template.palette.keys.sorted().firstOrNull { it !in finished } ?: template.palette.keys.minOrNull() ?: 1

        _uiState.update {
            it.copy(
                selectedTemplateId = template.id,
                activeColorNumber = firstAvailableColor,
                statusMessage = null,
                sessionTimeSeconds = 0L // Сбрасываем таймер при открытии
            )
        }
        startTimer()
    }

    fun closeTemplate() {
        timerJob?.cancel()
        _uiState.update { it.copy(selectedTemplateId = null, statusMessage = null) }
    }

    fun selectColor(colorNumber: Int) {
        if (colorNumber !in _uiState.value.completedColors) {
            _uiState.update { it.copy(activeColorNumber = colorNumber) }
        }
    }

    fun onCellTapped(row: Int, col: Int, isDrag: Boolean = false) {
        val state = _uiState.value
        val template = state.currentTemplate ?: return
        val expectedColor = template.grid.getOrNull(row)?.getOrNull(col) ?: 0
        if (expectedColor <= 0) return

        val key = "${row}_${col}"
        val currentMap = state.currentFilledCells.toMutableMap()
        val previouslyApplied = currentMap[key]

        if (previouslyApplied == expectedColor) return
        val activeColor = state.activeColorNumber
        if (activeColor in state.completedColors) return

        currentMap[key] = activeColor

        val updatedProgress = state.progressMap.toMutableMap().apply { put(template.id, currentMap) }
        _uiState.update { it.copy(progressMap = updatedProgress) }

        saveDebounceJob?.cancel()
        saveDebounceJob = viewModelScope.launch {
            delay(500L)
            repository.saveProgress(template.id, currentMap)
        }

        if (activeColor != expectedColor && !isDrag) {
            repository.vibrateWrongTap()
        }

        if (_uiState.value.isCurrentCompleted) {
            timerJob?.cancel() // Останавливаем таймер, если картинка готова
        }
    }

    fun resetCurrentTemplateProgress() {
        val template = _uiState.value.currentTemplate ?: return
        val updatedMap = _uiState.value.progressMap.toMutableMap().apply { put(template.id, emptyMap()) }
        _uiState.update { it.copy(progressMap = updatedMap, statusMessage = null, sessionTimeSeconds = 0L) }
        viewModelScope.launch { repository.saveProgress(template.id, emptyMap()) }
        startTimer()
    }

    fun saveCompletedArtwork(scaleMultiplier: Int) {
        val template = _uiState.value.currentTemplate ?: return
        viewModelScope.launch {
            repository.exportAndSaveArtwork(template, scaleMultiplier)
            _uiState.update { it.copy(artworks = repository.getArtworks(), statusMessage = "Сохранено (${scaleMultiplier}×)!") }
        }
    }

    fun openArtwork(artwork: ArtworkEntity) { _uiState.update { it.copy(selectedArtwork = artwork) } }
    fun closeArtwork() { _uiState.update { it.copy(selectedArtwork = null) } }
    fun shareArtwork(artwork: ArtworkEntity) { repository.shareArtwork(artwork) }
    fun deleteArtwork(artwork: ArtworkEntity) {
        viewModelScope.launch {
            repository.deleteArtwork(artwork)
            _uiState.update { it.copy(artworks = repository.getArtworks(), selectedArtwork = null) }
        }
    }
}