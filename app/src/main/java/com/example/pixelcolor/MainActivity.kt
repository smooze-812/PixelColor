package com.example.pixelcolor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pixelcolor.presentation.ArtworkViewScreen
import com.example.pixelcolor.presentation.ColoringScreen
import com.example.pixelcolor.presentation.GalleryScreen
import com.example.pixelcolor.presentation.PixelViewModel
import com.example.pixelcolor.ui.theme.PixelColorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelColorTheme {
                val viewModel: PixelViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when {
                            // Экран 3: Просмотр готовой работы
                            uiState.selectedArtwork != null -> {
                                BackHandler { viewModel.closeArtwork() }
                                ArtworkViewScreen(
                                    artwork = uiState.selectedArtwork!!,
                                    onBack = { viewModel.closeArtwork() },
                                    onShare = { viewModel.shareArtwork(it) },
                                    onDelete = { viewModel.deleteArtwork(it) }
                                )
                            }

                            // Экран 2: Раскрашивание выбранного шаблона
                            uiState.currentTemplate != null -> {
                                BackHandler { viewModel.closeTemplate() }
                                ColoringScreen(
                                    sessionTimeSeconds = uiState.sessionTimeSeconds,
                                    completedColors = uiState.completedColors,
                                    template = uiState.currentTemplate!!,
                                    filledCells = uiState.currentFilledCells,
                                    activeColorNumber = uiState.activeColorNumber,
                                    isCompleted = uiState.isCurrentCompleted,
                                    statusMessage = uiState.statusMessage,
                                    onBack = { viewModel.closeTemplate() },
                                    onSelectColor = { viewModel.selectColor(it) },
                                    onCellTap = { r, c, isDrag -> viewModel.onCellTapped(r, c, isDrag) },
                                    onSavePng = { scale -> viewModel.saveCompletedArtwork(scale) },
                                    onReset = { viewModel.resetCurrentTemplateProgress() }
                                )
                            }

                            // Экран 1: Галерея шаблонов и вкладка «Мои работы»
                            else -> {
                                GalleryScreen(
                                    uiState = uiState,
                                    onTemplateClick = { viewModel.openTemplate(it) },
                                    onArtworkClick = { viewModel.openArtwork(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}