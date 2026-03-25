@file:Suppress("ktlint:standard:function-naming")

package cash.z.ecc.android.sdk.demoapp.ui.screen.server.view

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.demoapp.ext.Testnet
import cash.z.ecc.android.sdk.demoapp.ext.isValid
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

@Preview(name = "Server")
@Composable
private fun ComposablePreview() {
    MaterialTheme {
        // TODO [#1090]: Demo: Add Compose Previews
        // TODO [#1090]: https://github.com/zcash/zcash-android-wallet-sdk/issues/1090
        // Server()
    }
}

const val YW_HOST_1 = "lwd1.zcash-infra.com" // NON-NLS
const val YW_HOST_2 = "lwd2.zcash-infra.com" // NON-NLS
const val YW_HOST_3 = "lwd3.zcash-infra.com" // NON-NLS
const val YW_HOST_4 = "lwd4.zcash-infra.com" // NON-NLS
const val YW_HOST_5 = "lwd5.zcash-infra.com" // NON-NLS
const val YW_HOST_6 = "lwd6.zcash-infra.com" // NON-NLS
const val YW_HOST_7 = "lwd7.zcash-infra.com" // NON-NLS
const val YW_HOST_8 = "lwd8.zcash-infra.com" // NON-NLS
const val YW_PORT = 9067

const val ZR_HOST = "zec.rocks" // NON-NLS
const val ZR_HOST_NA = "na.zec.rocks" // NON-NLS
const val ZR_HOST_SA = "sa.zec.rocks" // NON-NLS
const val ZR_HOST_EU = "eu.zec.rocks" // NON-NLS
const val ZR_HOST_AP = "ap.zec.rocks" // NON-NLS
const val ZR_PORT = 443

@Suppress("LongParameterList")
@Composable
fun Server(
    buildInNetwork: ZcashNetwork,
    onBack: () -> Unit,
    onServerChange: (LightWalletEndpoint) -> Unit,
    wallet: PersistableWallet,
    validationResult: ServerValidation,
    fastestServers: FastestServersResult?
) {
    Scaffold(
        topBar = { ServerTopAppBar(onBack) },
    ) { paddingValues ->
        ServerSwitch(
            buildInNetwork = buildInNetwork,
            wallet = wallet,
            onServerChange = onServerChange,
            validationResult = validationResult,
            modifier =
                Modifier.padding(
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = paddingValues.calculateBottomPadding() + 16.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            fastestServers = fastestServers
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ServerTopAppBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = stringResource(id = R.string.menu_server)) },
        navigationIcon = {
            IconButton(
                onClick = onBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
            }
        }
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun ServerSwitch(
    buildInNetwork: ZcashNetwork,
    onServerChange: (LightWalletEndpoint) -> Unit,
    wallet: PersistableWallet,
    fastestServers: FastestServersResult?,
    validationResult: ServerValidation,
    modifier: Modifier = Modifier,
) {
    Twig.info { "Currently selected wallet: ${wallet.toSafeString()}" }

    val context = LocalContext.current

    val options =
        buildList {
            if (buildInNetwork == ZcashNetwork.Mainnet) {
                add(LightWalletEndpoint(ZR_HOST, ZR_PORT, true))
                add(LightWalletEndpoint(ZR_HOST_NA, ZR_PORT, true))
                add(LightWalletEndpoint(ZR_HOST_SA, ZR_PORT, true))
                add(LightWalletEndpoint(ZR_HOST_EU, ZR_PORT, true))
                add(LightWalletEndpoint(ZR_HOST_AP, ZR_PORT, true))

                add(LightWalletEndpoint(YW_HOST_1, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_2, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_3, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_4, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_5, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_6, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_7, YW_PORT, true))
                add(LightWalletEndpoint(YW_HOST_8, YW_PORT, true))
            } else {
                add(LightWalletEndpoint.Testnet)
            }

            // Custom server
            if (contains(wallet.endpoint)) {
                // The custom server is defined as secured by default
                add(LightWalletEndpoint("", -1, true))
            } else {
                add(wallet.endpoint)
            }
        }.toMutableList()

    var selectedOptionIndex: Int by rememberSaveable { mutableIntStateOf(options.indexOf(wallet.endpoint)) }

    val initialCustomServerValue =
        options.last().run {
            if (options.last().isValid()) {
                stringResource(R.string.server_textfield_value, options.last().host, options.last().port)
            } else {
                ""
            }
        }
    var customServerTextFieldValue: String by rememberSaveable { mutableStateOf(initialCustomServerValue) }

    var customServerError: String? by rememberSaveable { mutableStateOf(null) }

    LaunchedEffect(key1 = validationResult) {
        when (validationResult) {
            is ServerValidation.InValid -> {
                customServerError = context.getString(R.string.server_textfield_error)
            }

            else -> {}
        }
    }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        when (fastestServers) {
            FastestServersResult.Measuring -> {
                Text(text = stringResource(R.string.server_fastest_servers))
                CircularProgressIndicator()
            }

            is FastestServersResult.Validating -> {
                if (fastestServers.servers.isNotEmpty()) {
                    Text(text = stringResource(R.string.server_fastest_servers))
                    fastestServers.servers.forEach {
                        Text(text = it.toString())
                    }
                    CircularProgressIndicator()
                }
            }

            is FastestServersResult.Done -> {
                if (fastestServers.servers.isNotEmpty()) {
                    Text(text = stringResource(R.string.server_fastest_servers))
                    fastestServers.servers.forEach {
                        Text(text = it.toString())
                    }
                }
            }

            null -> {
                // do nothing
            }
        }

        options.forEachIndexed { index, endpoint ->
            val isSelected = index == selectedOptionIndex
            val isLast = index == options.lastIndex

            if (!isLast) {
                LabeledRadioButton(
                    endpoint = endpoint,
                    changeClick = { selectedOptionIndex = index },
                    name = stringResource(id = R.string.server_textfield_value, endpoint.host, endpoint.port),
                    selected = isSelected,
                )
            } else {
                Column(
                    modifier = Modifier.animateContentSize()
                ) {
                    LabeledRadioButton(
                        endpoint = endpoint,
                        changeClick = { selectedOptionIndex = index },
                        name = stringResource(id = R.string.server_custom),
                        selected = isSelected,
                    )

                    if (isSelected) {
                        Spacer(modifier = Modifier.height(4.dp))

                        TextField(
                            value = customServerTextFieldValue,
                            onValueChange = {
                                customServerError = null
                                customServerTextFieldValue = it
                            },
                            placeholder = {
                                Text(text = stringResource(R.string.server_textfield_hint))
                            }
                        )

                        if (!customServerError.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = customServerError!!,
                                color = Color.Red,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = validationResult != ServerValidation.Running,
            onClick = {
                val selectedServer =
                    if (selectedOptionIndex == options.lastIndex) {
                        Twig.info { "Building custom server from: $customServerTextFieldValue" }

                        if (!validateCustomServerValue(customServerTextFieldValue)) {
                            customServerError = context.getString(R.string.server_textfield_error)
                            return@Button
                        }

                        customServerTextFieldValue.toEndpoint()
                    } else {
                        Twig.info { "Building  regular server from: ${options[selectedOptionIndex]}" }
                        options[selectedOptionIndex]
                    }

                Twig.info { "Selected server: $selectedServer" }
                onServerChange(selectedServer)
            },
            modifier = Modifier.fillMaxWidth(fraction = 0.75f)
        ) {
            Text(stringResource(id = R.string.server_save))
        }
    }
}

fun String.toEndpoint(): LightWalletEndpoint {
    val parts = split(":")
    return LightWalletEndpoint(parts[0], parts[1].toInt(), true)
}

// This regex validates server URLs with ports while ensuring:
// - Valid hostname format (excluding spaces and special characters)
// - Port numbers within the valid range (1-65535) and without leading zeros
// - Note that it doesn't cover other URL components like paths or query strings.
val regex = "^(([^:/?#\\s]+)://)?([^/?#\\s]+):([1-9][0-9]{3}|[1-5][0-9]{2}|[0-9]{1,2})$".toRegex()

fun validateCustomServerValue(customServer: String): Boolean = regex.matches(customServer)

@Composable
fun LabeledRadioButton(
    name: String,
    endpoint: LightWalletEndpoint,
    selected: Boolean,
    changeClick: (LightWalletEndpoint) -> Unit
) {
    Row(
        modifier =
            Modifier
                .wrapContentSize()
                .clickable { changeClick(endpoint) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { changeClick(endpoint) }
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier
                    .padding(all = 8.dp)
        )
    }
}
