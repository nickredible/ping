package uk.maynotfunction.ping.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import uk.maynotfunction.ping.R

@Composable
fun segmentedShapes(index: Int, count: Int): ListItemShapes =
    ListItemDefaults.segmentedShapes(index, count)

/** Cards one step brighter than the sheet (surfaceContainer), as in Android's own settings. */
@Composable
fun itemContainerColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
fun itemColors(): ListItemColors = ListItemDefaults.segmentedColors(containerColor = itemContainerColor())

fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}

@Composable
fun ItemIcon(@DrawableRes icon: Int) = Icon(painterResource(icon), contentDescription = null)

/** A whole-row switch, as in Tomato's settings. */
@Composable
fun SwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    supporting: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val toggle: (Boolean) -> Unit = {
        haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onCheckedChange(it)
    }
    SegmentedListItem(
        onClick = { toggle(!checked) },
        shapes = shapes,
        colors = itemColors(),
        leadingContent = { ItemIcon(icon) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = toggle,
                thumbContent = {
                    Icon(
                        painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
    ) { Text(title) }
}

@Composable
fun ClickItem(
    title: String,
    supporting: String?,
    shapes: ListItemShapes,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = itemColors(),
        leadingContent = { ItemIcon(icon) },
        supportingContent = supporting?.let { { Text(it, maxLines = 2) } },
    ) { Text(title) }
}

/** Read-only row for telemetry. */
@Composable
fun InfoItem(title: String, value: String, shapes: ListItemShapes, @DrawableRes icon: Int) {
    SegmentedListItem(
        shapes = shapes,
        colors = itemColors(),
        leadingContent = { ItemIcon(icon) },
        supportingContent = { Text(value) },
    ) { Text(title) }
}

/** Connected button group (M3 Expressive) acting as a single-choice selector. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(option)
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/** Single text field dialog used by the relay/map settings. */
@Composable
fun TextFieldDialog(
    title: String,
    message: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    neutralLabel: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder) },
                    singleLine = !secret && value.length < 60,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (neutralLabel != null && onNeutral != null) TextButton(onClick = onNeutral) { Text(neutralLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
