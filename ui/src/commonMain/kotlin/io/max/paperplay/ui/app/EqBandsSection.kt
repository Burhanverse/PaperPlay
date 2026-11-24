package io.max.paperplay.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.theme.LocalReduceMotion

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqBandsSection(
    profile: ParametricEqProfile,
    selectedBandId: Int,
    expandedBandId: Int,
    listState: LazyListState,
    bottomPadding: Dp,
    onAddBand: () -> Unit,
    onLoadIso31: () -> Unit,
    onResetFlat: () -> Unit,
    onToggleExpand: (ParametricBand) -> Unit,
    onChangeBand: (ParametricBand) -> Unit,
    onDeleteBand: (Int) -> Unit,
    onOpenNumeric: (ParametricBand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val cardShape = appShape(20.dp)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
    ) {
        item(key = "actions") {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = profile.bands.size < ParametricEqProfile.MAX_PEQ_BANDS,
                    onClick = onAddBand,
                ) {
                    AppIcon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add band (${profile.bands.size}/31)", maxLines = 1)
                }
                OutlinedButton(onClick = onLoadIso31) {
                    AppIcon(Icons.Rounded.GraphicEq, null)
                    Spacer(Modifier.width(6.dp))
                    Text("31-Band ISO", maxLines = 1)
                }
                OutlinedButton(onClick = onResetFlat) {
                    AppIcon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset flat", maxLines = 1)
                }
            }
        }

        item(key = "header") {
            SectionLabel("Bands (${profile.bands.size})")
        }

        itemsIndexed(profile.bands, key = { _, b -> b.id }) { idx, band ->
            val isSelected = band.id == selectedBandId
            val isExpanded = band.id == expandedBandId
            val bg = if (isSelected || isExpanded) scheme.primaryContainer.copy(alpha = 0.42f) else scheme.surfaceContainerLow

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .partSurface(LookGroup.Cards, bg, cardShape)
                    .border(
                        width = if (isExpanded) 1.5.dp else 0.dp,
                        color = if (isExpanded) scheme.primary else Color.Transparent,
                        shape = cardShape,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onToggleExpand(band) }
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (band.enabled) scheme.primary else scheme.outlineVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (band.enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                fmtHz(band.freqHz),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected || isExpanded) FontWeight.Bold else null,
                                maxLines = 1,
                            )
                            Text(
                                "${band.type.shortLabel} · ${band.channel.shortLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary,
                                maxLines = 1,
                            )
                        }
                        Text(
                            if (band.type.usesGain) "${fmtDb(band.gainDb)} dB   Q ${fmtQ(band.q)}"
                            else "Q ${fmtQ(band.q)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = band.enabled,
                        onCheckedChange = { en -> onChangeBand(band.copy(enabled = en)) },
                        modifier = Modifier.semantics { contentDescription = "Enable band ${idx + 1}" },
                    )
                    if (profile.bands.size > 1) {
                        IconButton(onClick = { onDeleteBand(band.id) }) {
                            AppIcon(Icons.Rounded.Delete, "Delete band ${idx + 1}")
                        }
                    }
                    AppIcon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        if (isExpanded) "Collapse band" else "Expand band",
                        tint = scheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = if (reduceMotion) EnterTransition.None else androidx.compose.animation.expandVertically(),
                    exit = if (reduceMotion) ExitTransition.None else androidx.compose.animation.shrinkVertically(),
                ) {
                    Column {
                        RowDivider()
                        BandPrecisionControls(
                            band = band,
                            title = null,
                            canDelete = false,
                            onChange = onChangeBand,
                            onDelete = {},
                            onEditNumbers = { onOpenNumeric(band) },
                        )
                    }
                }
            }
        }
    }
}
