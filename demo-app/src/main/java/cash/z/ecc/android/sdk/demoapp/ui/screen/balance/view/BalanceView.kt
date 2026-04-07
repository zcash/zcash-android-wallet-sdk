package cash.z.ecc.android.sdk.demoapp.ui.screen.balance.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.demoapp.ui.screen.home.viewmodel.SendState
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.ext.toUsdString
import cash.z.ecc.android.sdk.fixture.AccountBalanceFixture
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.toZecString
import java.math.BigDecimal
import java.util.Locale

@Preview(name = "Balance")
@Suppress("ktlint:standard:function-naming", "MagicNumber")
@Composable
private fun ComposablePreview() {
    MaterialTheme {
        Balance(
            exchangeRateUsd = BigDecimal(50),
            accountBalance = AccountBalanceFixture.new(),
            sendState = SendState.None,
            onBack = {},
            onShieldFunds = {},
            onRefresh = {}
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming", "LongParameterList")
fun Balance(
    exchangeRateUsd: BigDecimal?,
    accountBalance: AccountBalance,
    sendState: SendState,
    onShieldFunds: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(topBar = {
        BalanceTopAppBar(
            onBack,
            onRefresh
        )
    }) { paddingValues ->

        BalanceMainContent(
            paddingValues = paddingValues,
            exchangeRateUsd = exchangeRateUsd,
            accountBalance = accountBalance,
            sendState = sendState,
            onShieldFunds = onShieldFunds
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
private fun BalanceTopAppBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    TopAppBar(
        title = { Text(text = stringResource(id = R.string.menu_balance)) },
        navigationIcon = {
            IconButton(
                onClick = onBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Autorenew,
                    contentDescription = stringResource(id = R.string.balances_refresh)
                )
            }
        }
    )
}

@Composable
@Suppress("ktlint:standard:function-naming", "LongMethod")
private fun BalanceMainContent(
    exchangeRateUsd: BigDecimal?,
    accountBalance: AccountBalance,
    paddingValues: PaddingValues,
    sendState: SendState,
    onShieldFunds: () -> Unit
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = paddingValues.calculateTopPadding())
    ) {
        Text(stringResource(id = R.string.balance_orchard))
        Text(
            stringResource(
                id = R.string.balance_available_amount_format,
                accountBalance.orchard.available.toZecString(Locale.getDefault()),
                exchangeRateUsd
                    ?.multiply(accountBalance.orchard.available.convertZatoshiToZec())
                    .toUsdString(Locale.getDefault())
            )
        )
        Text(
            stringResource(
                id = R.string.balance_pending_amount_format,
                accountBalance.orchard.pending.toZecString(Locale.getDefault()),
                exchangeRateUsd
                    ?.multiply(accountBalance.orchard.pending.convertZatoshiToZec())
                    .toUsdString(Locale.getDefault())
            )
        )

        Spacer(Modifier.padding(8.dp))

        Text(stringResource(id = R.string.balance_sapling))
        Text(
            stringResource(
                id = R.string.balance_available_amount_format,
                accountBalance.sapling.available.toZecString(Locale.getDefault()),
                exchangeRateUsd
                    ?.multiply(accountBalance.sapling.available.convertZatoshiToZec())
                    .toUsdString(Locale.getDefault())
            )
        )
        Text(
            stringResource(
                id = R.string.balance_pending_amount_format,
                accountBalance.sapling.pending.toZecString(Locale.getDefault()),
                exchangeRateUsd
                    ?.multiply(accountBalance.sapling.pending.convertZatoshiToZec())
                    .toUsdString(Locale.getDefault())
            )
        )

        Spacer(Modifier.padding(8.dp))

        Text(stringResource(id = R.string.balance_transparent))
        Text(
            stringResource(
                id = R.string.balance_available_amount_format,
                accountBalance.unshielded.toZecString(Locale.getDefault()),
                exchangeRateUsd
                    ?.multiply(accountBalance.unshielded.convertZatoshiToZec())
                    .toUsdString(Locale.getDefault())
            )
        )

        // This check is not entirely correct - it does not calculate the resulting fee with the new Proposal API
        if (accountBalance.unshielded.value > 0L) {
            // Note this implementation does not guard against multiple clicks
            Button(onClick = onShieldFunds) {
                Text(stringResource(id = R.string.action_shield))
            }
        }

        // Eventually there should be something to clear the status
        Text(stringResource(id = R.string.send_status, sendState.toString()))
    }
}
