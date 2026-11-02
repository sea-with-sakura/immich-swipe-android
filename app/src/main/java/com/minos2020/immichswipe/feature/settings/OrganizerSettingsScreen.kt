package com.minos2020.immichswipe.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minos2020.immichswipe.R
import com.minos2020.immichswipe.core.AppTheme
import com.minos2020.immichswipe.core.CardDisplayMode
import com.minos2020.immichswipe.core.PlaybackBehavior

/** Only settings that affect the album-organizing workflow. */
@Composable
fun OrganizerSettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(stringResource(R.string.settings_section_appearance), Icons.Default.Palette) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_theme_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton(stringResource(R.string.settings_theme_system), Icons.Default.SettingsSuggest,
                        state.themeMode == AppTheme.SYSTEM, { viewModel.setThemeMode(AppTheme.SYSTEM) }, Modifier.weight(1f))
                    ThemeButton(stringResource(R.string.settings_theme_light), Icons.Default.LightMode,
                        state.themeMode == AppTheme.LIGHT, { viewModel.setThemeMode(AppTheme.LIGHT) }, Modifier.weight(1f))
                    ThemeButton(stringResource(R.string.settings_theme_dark), Icons.Default.DarkMode,
                        state.themeMode == AppTheme.DARK, { viewModel.setThemeMode(AppTheme.DARK) }, Modifier.weight(1f))
                }
                Text(stringResource(R.string.settings_display_mode_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton(stringResource(R.string.settings_display_mode_fill), Icons.Default.AspectRatio,
                        state.defaultCardDisplayMode == CardDisplayMode.FILL, { viewModel.setDefaultCardDisplayMode(CardDisplayMode.FILL) }, Modifier.weight(1f))
                    ThemeButton(stringResource(R.string.settings_display_mode_fit), Icons.Default.FitScreen,
                        state.defaultCardDisplayMode == CardDisplayMode.FIT, { viewModel.setDefaultCardDisplayMode(CardDisplayMode.FIT) }, Modifier.weight(1f))
                }
            }
        }
        SettingsSection(stringResource(R.string.settings_section_tri), Icons.Default.PhotoLibrary) {
            Column(Modifier.padding(16.dp)) {
                SettingsToggleItemSmall(stringResource(R.string.settings_include_archived_label), state.includeArchived,
                    { viewModel.setIncludeArchived(it) }, Icons.Default.Inventory2)
                SettingsToggleItemSmall(stringResource(R.string.settings_swipe_invert_label), state.isSwipeInverted,
                    { viewModel.setSwipeInverted(it) }, Icons.Default.Swipe)
            }
        }
        SettingsSection(stringResource(R.string.settings_video_behavior_label), Icons.Default.PlayCircle) {
            Column(Modifier.padding(16.dp)) {
                PlaybackOption(stringResource(R.string.settings_video_pause), state.playbackBehavior == PlaybackBehavior.PAUSE_OTHERS,
                    { viewModel.setPlaybackBehavior(PlaybackBehavior.PAUSE_OTHERS) })
                PlaybackOption(stringResource(R.string.settings_video_ignore), state.playbackBehavior == PlaybackBehavior.IGNORE,
                    { viewModel.setPlaybackBehavior(PlaybackBehavior.IGNORE) })
            }
        }
    }
}
