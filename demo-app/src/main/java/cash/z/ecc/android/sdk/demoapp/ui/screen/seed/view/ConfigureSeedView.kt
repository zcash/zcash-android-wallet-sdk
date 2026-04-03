package cash.z.ecc.android.sdk.demoapp.ui.screen.seed.view

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.demoapp.ext.defaultForNetwork
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlin.time.Instant

@Preview(name = "Seed")
@Composable
@Suppress("ktlint:standard:function-naming")
private fun ComposablePreview() {
    MaterialTheme {
        Seed(
            ZcashNetwork.Mainnet,
            onExistingWallet = {},
            onNewWallet = {},
            onBirthdayEstimation = {},
            estimatedHeight = null
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
fun Seed(
    zcashNetwork: ZcashNetwork,
    onExistingWallet: (PersistableWallet) -> Unit,
    onNewWallet: () -> Unit,
    onBirthdayEstimation: (Instant) -> Unit,
    estimatedHeight: BlockHeight?,
) {
    Scaffold(topBar = {
        ConfigureSeedTopAppBar()
    }) { paddingValues ->
        ConfigureSeedMainContent(
            paddingValues = paddingValues,
            zcashNetwork = zcashNetwork,
            onExistingWallet = onExistingWallet,
            onNewWallet = onNewWallet,
            onBirthdayEstimation = onBirthdayEstimation,
            estimatedHeight = estimatedHeight
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
private fun ConfigureSeedTopAppBar() {
    TopAppBar(
        title = { Text(text = stringResource(id = R.string.configure_seed)) }
    )
}

@Composable
@Suppress("ktlint:standard:function-naming", "LongParameterList")
private fun ConfigureSeedMainContent(
    paddingValues: PaddingValues,
    zcashNetwork: ZcashNetwork,
    onExistingWallet: (PersistableWallet) -> Unit,
    onNewWallet: () -> Unit,
    onBirthdayEstimation: (Instant) -> Unit,
    estimatedHeight: BlockHeight?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = paddingValues.calculateTopPadding())
            .padding(horizontal = 8.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(color = Color.Gray))
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val newWallet =
                    PersistableWallet(
                        network = zcashNetwork,
                        endpoint = LightWalletEndpoint.defaultForNetwork(zcashNetwork),
                        birthday = WalletFixture.Alice.getBirthday(zcashNetwork),
                        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
                        walletInitMode = WalletInitMode.RestoreWallet,
                    )
                onExistingWallet(newWallet)
            }
        ) {
            Text(text = stringResource(id = R.string.person_alyssa))
        }
        Button(
            onClick = {
                val newWallet =
                    PersistableWallet(
                        network = zcashNetwork,
                        endpoint = LightWalletEndpoint.defaultForNetwork(zcashNetwork),
                        birthday = WalletFixture.Ben.getBirthday(zcashNetwork),
                        seedPhrase = SeedPhrase.new(WalletFixture.Ben.seedPhrase),
                        walletInitMode = WalletInitMode.RestoreWallet,
                    )
                onExistingWallet(newWallet)
            }
        ) {
            Text(text = stringResource(R.string.person_ben))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(color = Color.Gray))
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNewWallet
        ) {
            Text(text = stringResource(id = R.string.seed_random))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(color = Color.Gray))
        Spacer(modifier = Modifier.height(16.dp))

        RestoreWalletSection(zcashNetwork, onExistingWallet, onBirthdayEstimation, estimatedHeight)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming", "LongMethod")
private fun RestoreWalletSection(
    zcashNetwork: ZcashNetwork,
    onExistingWallet: (PersistableWallet) -> Unit,
    onBirthdayEstimation: (Instant) -> Unit,
    estimatedHeight: BlockHeight?,
    modifier: Modifier = Modifier
) {
    var openBdEstimateDialog by remember { mutableStateOf(false) }

    var walletBirthdayString by rememberSaveable {
        mutableStateOf(estimatedHeight?.value?.toString())
    }

    @Composable
    fun EstimateBdFromDatePicker() {
        DatePickerDialog(
            onDismissRequest = { openBdEstimateDialog = false },
            confirmButton = {}
        ) {
            val state = rememberDatePickerState()
            DatePicker(state = state)
            state.selectedDateMillis?.let {
                Twig.debug { "User selected date value: $it" }
                walletBirthdayString = null
                openBdEstimateDialog = false
                onBirthdayEstimation(Instant.fromEpochMilliseconds(it))
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val context = LocalContext.current

        var seedPhraseString by rememberSaveable {
            mutableStateOf("")
        }
        var requiredDataIsEmpty by rememberSaveable {
            mutableStateOf(true)
        }

        fun preValidateInput() {
            requiredDataIsEmpty = seedPhraseString.isEmpty()
        }

        fun validateAndRestore() {
            try {
                val seedPhrase = SeedPhrase.new(seedPhraseString)

                val blockHeight =
                    walletBirthdayString?.toLongOrNull()?.let { blockHeight ->
                        BlockHeight.new(blockHeight)
                    } ?: zcashNetwork.saplingActivationHeight // fallback to last known valid value

                val wallet =
                    PersistableWallet(
                        network = zcashNetwork,
                        endpoint = LightWalletEndpoint.defaultForNetwork(zcashNetwork),
                        birthday = blockHeight,
                        seedPhrase = seedPhrase,
                        walletInitMode = WalletInitMode.RestoreWallet,
                    )
                onExistingWallet(wallet)
            } catch (e: IllegalArgumentException) {
                val errorMessage = e.message ?: "Unknown error"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                Twig.error(e) { errorMessage }
            }
        }

        TextField(
            value = seedPhraseString,
            onValueChange = {
                seedPhraseString = it
                preValidateInput()
            },
            minLines = 4,
            label = { Text(stringResource(id = R.string.seed_custom)) }
        )
        TextField(
            value =
                when {
                    walletBirthdayString != null -> {
                        walletBirthdayString!!
                    }

                    estimatedHeight != null -> {
                        walletBirthdayString = estimatedHeight.value.toString()
                        walletBirthdayString!!
                    }

                    else -> {
                        ""
                    }
                },
            onValueChange = {
                Twig.debug { "New BD value: $it" }
                // filter input to contain only digits
                walletBirthdayString = it.filter { char -> char.isDigit() }
                preValidateInput()
            },
            maxLines = 1,
            keyboardOptions =
                KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Number
                ),
            label = { Text(stringResource(id = R.string.seed_birthday_optional)) }
        )
        Button(
            onClick = { openBdEstimateDialog = true }
        ) {
            Text(text = stringResource(id = R.string.seed_estimate_bd_btn))
        }
        Button(
            enabled = !requiredDataIsEmpty,
            onClick = { validateAndRestore() }
        ) {
            Text(text = stringResource(id = R.string.seed_restore))
        }
    }

    if (openBdEstimateDialog) {
        EstimateBdFromDatePicker()
    }
}
