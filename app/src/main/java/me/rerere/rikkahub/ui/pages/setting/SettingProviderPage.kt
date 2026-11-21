package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ProviderPreset
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Undo
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.QRCodeScannerDialog
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.RECOMMENDED_PROVIDERS
import me.rerere.rikkahub.data.datastore.createProviderFromPreset
import me.rerere.rikkahub.data.datastore.isAdded
import me.rerere.rikkahub.data.datastore.presetBaseUrl
import me.rerere.rikkahub.data.datastore.usableProviderPresets
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.components.ui.rememberGalleryMediaPicker
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProviderSetting?>(null) }
    // 恢复内置服务商的确认框
    var showRestoreDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.providers.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        vm.updateSettings { it.copy(providers = newProviders) }
    }

    val filteredProviders = remember(settings.providers, searchQuery) {
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 设置是异步写入的，等新提供商真正出现在列表里再滚过去；新建时会跳详情页，所以要能跨页面保存
    var pendingScrollId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingScrollId, filteredProviders) {
        val id = pendingScrollId ?: return@LaunchedEffect
        val index = filteredProviders.indexOfFirst { it.id.toString() == id }
        if (index >= 0) {
            lazyListState.animateScrollToItem(index)
            pendingScrollId = null
        }
    }

    fun addProvider(provider: ProviderSetting) {
        // 搜索词可能把新提供商过滤掉
        searchQuery = ""
        pendingScrollId = provider.id.toString()
        vm.updateSettings { latest ->
            // 连点时第二次写入已经能看到第一次的结果，避免出现重复 id
            if (latest.providers.any { it.id == provider.id }) return@updateSettings latest
            latest.copy(
                providers = listOf(provider) + latest.providers,
                // 重新添加被删除的内置提供商时解除隐藏
                hiddenProviderIds = latest.hiddenProviderIds - provider.id,
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    // 推荐提供商保留固定 id，读盘后才能按 id 补回不落盘的描述
                    RecommendProviderButton(
                        addedIds = settings.providers.map { it.id }.toSet(),
                        addedProviders = settings.providers,
                        onAdd = { provider ->
                            addProvider(provider.copyProvider())
                        },
                        onAddFromDirectory = { provider ->
                            addProvider(provider)
                            navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                        },
                    )
                    ImportProviderButton {
                        addProvider(it.copyProvider(Uuid.random()))
                    }
                    AddButton(
                        showRestore = settings.hiddenProviderIds.isNotEmpty(),
                        addedProviders = settings.providers,
                        onRestore = { showRestoreDialog = true },
                    ) { provider ->
                        addProvider(provider)
                        navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )


            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) +
                    PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                items(filteredProviders, key = { it.id }) { provider ->
                    ReorderableItem(
                        state = reorderableState,
                        key = provider.id
                    ) { isDragging ->
                        ProviderItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(longPressReorder(isDragging, enabled = searchQuery.isBlank())),
                            provider = provider,
                            onClick = {
                                navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                            },
                            onDelete = {
                                deleteTarget = provider
                            }
                        )
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                vm.updateSettings { latest ->
                    latest.copy(
                        providers = latest.providers.filter { it.id != target.id },
                        // 删除的内置提供商记下 id，规整时才不会补回来
                        hiddenProviderIds = (latest.hiddenProviderIds + target.id).distinct(),
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.setting_provider_page_delete_dialog_text))
    }

    // 恢复入口放在“添加提供商”弹窗里并带确认框，避免被误当成刷新列表的按钮
    RikkaConfirmDialog(
        show = showRestoreDialog,
        title = stringResource(R.string.setting_provider_page_restore_built_in),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRestoreDialog = false
            vm.updateSettings { it.copy(hiddenProviderIds = emptyList()) }
            toaster.show(
                context.getString(R.string.setting_provider_page_restore_built_in_success),
                type = ToastType.Success
            )
        },
        onDismiss = { showRestoreDialog = false },
    ) {
        Text(stringResource(R.string.setting_provider_page_restore_built_in_dialog))
    }
}

@Composable
private fun RecommendProviderButton(
    addedIds: Set<Uuid>,
    addedProviders: List<ProviderSetting>,
    onAdd: (ProviderSetting) -> Unit,
    onAddFromDirectory: (ProviderSetting) -> Unit,
) {
    val toaster = LocalToaster.current
    var showSheet by remember { mutableStateOf(false) }
    val importSuccessMessage = stringResource(R.string.setting_provider_page_import_success)

    IconButton(
        onClick = { showSheet = true }
    ) {
        Icon(HugeIcons.Sparkles, contentDescription = stringResource(R.string.setting_provider_page_recommend))
    }

    if (showSheet) {
        // 订阅目录版本号: 目录在 App 启动后异步装载/后台刷新, 装好后这里会自动重组
        val catalogVersion by ModelRegistry.catalogVersion.collectAsStateWithLifecycle()
        val presets = remember(catalogVersion) { usableProviderPresets() }
        var directoryQuery by remember { mutableStateOf("") }
        val filteredPresets = remember(presets, directoryQuery) {
            if (directoryQuery.isBlank()) {
                presets
            } else {
                presets.filter { preset ->
                    preset.displayName.contains(directoryQuery, ignoreCase = true) ||
                        preset.id.contains(directoryQuery, ignoreCase = true) ||
                        preset.baseUrl?.contains(directoryQuery, ignoreCase = true) == true
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_recommend),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextField(
                    value = directoryQuery,
                    onValueChange = { directoryQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                    leadingIcon = {
                        Icon(HugeIcons.Search01, contentDescription = null)
                    },
                    trailingIcon = {
                        if (directoryQuery.isNotEmpty()) {
                            IconButton(onClick = { directoryQuery = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                // 搜索时只看过滤结果, 没搜索时推荐卡片和目录一起滚动, 一滑到底
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    if (directoryQuery.isBlank()) {
                        item(key = "recommended") {
                            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                                RECOMMENDED_PROVIDERS.fastForEachIndexed { index, provider ->
                                    RecommendProviderItem(
                                        provider = provider,
                                        added = provider.id in addedIds,
                                        shape = cardGroupItemShape(index, RECOMMENDED_PROVIDERS.size),
                                        onAdd = {
                                            onAdd(provider)
                                            toaster.show(
                                                importSuccessMessage,
                                                type = ToastType.Success
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        item(key = "directory_header") {
                            Text(
                                text = stringResource(R.string.setting_provider_page_all_providers),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    if (presets.isEmpty()) {
                        item(key = "directory_empty") {
                            Text(
                                text = stringResource(R.string.setting_provider_page_directory_not_loaded),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(filteredPresets, key = { it.id }) { preset ->
                            DirectoryProviderItem(
                                preset = preset,
                                added = preset.isAdded(addedProviders),
                                onAdd = {
                                    onAddFromDirectory(createProviderFromPreset(preset))
                                    showSheet = false
                                    toaster.show(
                                        importSuccessMessage,
                                        type = ToastType.Success
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// models.dev 目录里的供应商：整行可点，副标题显示接入地址和模型数
@Composable
private fun DirectoryProviderItem(
    preset: ProviderPreset,
    added: Boolean,
    onAdd: () -> Unit,
) {
    val host = presetBaseUrl(preset)?.let { runCatching { Uri.parse(it).host }.getOrNull() }
    Surface(
        onClick = onAdd,
        enabled = !added,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = preset.displayName,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        Text(
                            text = listOfNotNull(
                                host,
                                stringResource(R.string.setting_provider_page_models_count, preset.chatModelCount),
                            ).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (added) {
                Icon(HugeIcons.Tick02, contentDescription = "已添加")
            } else {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.setting_provider_page_add))
            }
        }
    }
}

@Composable
private fun RecommendProviderItem(
    provider: ProviderSetting,
    added: Boolean,
    shape: Shape,
    onAdd: () -> Unit
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AutoAIIcon(
                        name = provider.name,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        provider.shortDescription()
                    }
                }
            }
            FilledTonalIconButton(onClick = onAdd, enabled = !added) {
                if (added) {
                    Icon(HugeIcons.Tick02, contentDescription = "已添加")
                } else {
                    Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.setting_provider_page_add))
                }
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QRCodeScannerDialog(
            onResult = { content ->
                showScanner = false
                handleScannedQRCode(content, onAdd, toaster, context)
            },
            onPermissionDenied = {
                showScanner = false
                toaster.show(context.getString(R.string.setting_provider_page_no_permission), type = ToastType.Error)
            },
            onDismissRequest = { showScanner = false },
        )
    }

    // 直接拉起系统相册选二维码图片，不走文档管理器
    val pickQrImage = rememberGalleryMediaPicker { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(HugeIcons.FileImport, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 主要操作：扫描二维码
                        Button(
                            onClick = {
                                showImportDialog = false
                                showScanner = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Camera01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 次要操作：从相册选择
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickQrImage()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleScannedQRCode(
    content: String,
    onAdd: (ProviderSetting) -> Unit,
    toaster: me.rerere.ui.toast.ToasterState,
    context: android.content.Context
) {
    runCatching {
        val setting = decodeProviderSetting(content)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: me.rerere.ui.toast.ToasterState,
    context: android.content.Context
) {
    runCatching {
        // 使用ImageUtils解析二维码
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}


// 新建时顶部选类型，其余配置到详情页里填；下面直接列出全部供应商，一键添加；删除过内置提供商时末尾提供恢复入口
@Composable
private fun AddButton(
    showRestore: Boolean,
    addedProviders: List<ProviderSetting>,
    onRestore: () -> Unit,
    onAdd: (ProviderSetting) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val templates = remember {
        listOf(
            ProviderSetting.OpenAI(),
            ProviderSetting.Google(),
            ProviderSetting.Claude(),
            ProviderSetting.ChatGPT(),
            ProviderSetting.Grok(),
        )
    }
    val catalogVersion by ModelRegistry.catalogVersion.collectAsStateWithLifecycle()
    val presets = remember(catalogVersion) { usableProviderPresets() }

    IconButton(
        onClick = { showSheet = true }
    ) {
        Icon(HugeIcons.Add01, "Add")
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_add_provider),
                    style = MaterialTheme.typography.titleLarge,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    item(key = "templates") {
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            templates.fastForEachIndexed { index, template ->
                                Surface(
                                    onClick = {
                                        showSheet = false
                                        onAdd(template.copyProvider(Uuid.random()))
                                    },
                                    shape = cardGroupItemShape(index, templates.size),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val typeName = template.typeName()
                                        AutoAIIcon(
                                            name = typeName,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Text(
                                            text = typeName,
                                            style = MaterialTheme.typography.titleMediumEmphasized,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                                    }
                                }
                            }

                            if (showRestore) {
                                Surface(
                                    onClick = {
                                        showSheet = false
                                        onRestore()
                                    },
                                    shape = cardGroupItemShape(0, 1),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(HugeIcons.Undo, contentDescription = null)
                                        Text(
                                            text = stringResource(R.string.setting_provider_page_restore_built_in),
                                            style = MaterialTheme.typography.titleMediumEmphasized,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "directory_header") {
                        Text(
                            text = stringResource(R.string.setting_provider_page_all_providers),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (presets.isEmpty()) {
                        item(key = "directory_empty") {
                            Text(
                                text = stringResource(R.string.setting_provider_page_directory_not_loaded),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(presets, key = { it.id }) { preset ->
                            DirectoryProviderItem(
                                preset = preset,
                                added = preset.isAdded(addedProviders),
                                onAdd = {
                                    onAdd(createProviderFromPreset(preset))
                                    showSheet = false
                                    toaster.show(
                                        context.getString(R.string.setting_provider_page_import_success),
                                        type = ToastType.Success
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 类型的显示名，和用户可改的 ProviderSetting.name 分开
@Composable
private fun ProviderSetting.typeName(): String = when (this) {
    is ProviderSetting.OpenAI -> "OpenAI"
    is ProviderSetting.Google -> "Google"
    is ProviderSetting.Claude -> "Claude"
    is ProviderSetting.ChatGPT -> stringResource(R.string.setting_provider_page_type_subscription, "ChatGPT")
    is ProviderSetting.Grok -> stringResource(R.string.setting_provider_page_type_subscription, "Grok")
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) {
                CustomColors.listItemColors.containerColor
            } else MaterialTheme.colorScheme.errorContainer,
        ),
        onClick = {
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        provider.shortDescription()
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                    if (provider.name == "AiHubMix") {
                        Tag(type = TagType.INFO) {
                            Text("10% 优惠")
                        }
                    }
                }
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}
