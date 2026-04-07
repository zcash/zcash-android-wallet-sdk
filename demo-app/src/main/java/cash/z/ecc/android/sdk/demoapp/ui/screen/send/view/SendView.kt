package cash.z.ecc.android.sdk.demoapp.ui.screen.send.view

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.demoapp.MINIMAL_WEIGHT
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.demoapp.ui.screen.home.viewmodel.SendState
import cash.z.ecc.android.sdk.demoapp.util.fromResources
import cash.z.ecc.android.sdk.fixture.AccountBalanceFixture
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.MonetarySeparators
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.ZecSendExt
import cash.z.ecc.android.sdk.model.ZecString
import cash.z.ecc.android.sdk.model.ZecStringExt
import cash.z.ecc.android.sdk.model.toZecString
import java.util.Locale

@Preview(name = "Send")
@Composable
@Suppress("ktlint:standard:function-naming")
private fun ComposablePreview() {
    MaterialTheme {
        Send(
            accountBalance = AccountBalanceFixture.new(),
            sendState = SendState.None,
            onSend = {},
            onGetProposal = {},
            onGetProposalFromUri = {},
            onBack = {},
            sendTransactionProposal = null,
            sendTransactionProposalFromUri = null
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming", "LongParameterList")
fun Send(
    accountBalance: AccountBalance,
    sendState: SendState,
    onSend: (ZecSend) -> Unit,
    onGetProposal: (ZecSend) -> Unit,
    onGetProposalFromUri: (String) -> Unit,
    onBack: () -> Unit,
    sendTransactionProposal: Proposal?,
    sendTransactionProposalFromUri: Proposal?,
) {
    Scaffold(topBar = {
        SendTopAppBar(onBack)
    }) { paddingValues ->
        SendMainContent(
            paddingValues = paddingValues,
            accountBalance = accountBalance,
            sendState = sendState,
            onSend = onSend,
            onGetProposal = onGetProposal,
            onGetProposalFromUri = onGetProposalFromUri,
            sendTransactionProposal = sendTransactionProposal,
            sendTransactionProposalFromUri = sendTransactionProposalFromUri
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
private fun SendTopAppBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = stringResource(id = R.string.menu_send)) },
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

@Composable
@Suppress("LongMethod", "ktlint:standard:function-naming", "LongParameterList")
private fun SendMainContent(
    paddingValues: PaddingValues,
    accountBalance: AccountBalance,
    sendState: SendState,
    onSend: (ZecSend) -> Unit,
    onGetProposal: (ZecSend) -> Unit,
    onGetProposalFromUri: (String) -> Unit,
    sendTransactionProposal: Proposal?,
    sendTransactionProposalFromUri: Proposal?,
) {
    val context = LocalContext.current
    val monetarySeparators = MonetarySeparators.current(locale = Locale.US)
    val allowedCharacters = ZecString.allowedCharacters(monetarySeparators)

    var amountZecString by rememberSaveable {
        mutableStateOf("")
    }
    var recipientAddressString by rememberSaveable {
        mutableStateOf("")
    }
    var memoString by rememberSaveable { mutableStateOf("") }

    var zip321String by rememberSaveable { mutableStateOf("") }

    var validation by rememberSaveable {
        mutableStateOf<Set<ZecSendExt.ZecSendValidation.Invalid.ValidationError>>(emptySet())
    }

    Column(
        Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(top = paddingValues.calculateTopPadding())
    ) {
        Text(text = stringResource(id = R.string.send_available_balance))
        Row(Modifier.fillMaxWidth()) {
            Text(text = accountBalance.sapling.available.toZecString(Locale.getDefault()))
        }

        TextField(
            value = amountZecString,
            onValueChange = { newValue ->
                if (!ZecStringExt.filterContinuous(context, monetarySeparators, newValue)) {
                    return@TextField
                }
                amountZecString = newValue.filter { allowedCharacters.contains(it) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(id = R.string.send_amount)) }
        )

        Spacer(Modifier.size(8.dp))

        TextField(
            value = recipientAddressString,
            onValueChange = { recipientAddressString = it },
            label = { Text(stringResource(id = R.string.send_to_address)) }
        )

        val zcashNetwork = ZcashNetwork.fromResources(context)
        Column(
            Modifier
                .fillMaxWidth()
        ) {
            // Alice's addresses
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Button({ recipientAddressString = WalletFixture.Alice.getAddresses(zcashNetwork).unified }) {
                    Text(text = stringResource(id = R.string.send_alyssa_unified))
                }

                Spacer(Modifier.size(8.dp))

                Button({ recipientAddressString = WalletFixture.Alice.getAddresses(zcashNetwork).sapling }) {
                    Text(text = stringResource(id = R.string.send_alyssa_sapling))
                }

                Spacer(Modifier.size(8.dp))

                Button({ recipientAddressString = WalletFixture.Alice.getAddresses(zcashNetwork).transparent }) {
                    Text(text = stringResource(id = R.string.send_alyssa_transparent))
                }
            }
            // Bob's addresses
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Button({ recipientAddressString = WalletFixture.Ben.getAddresses(zcashNetwork).unified }) {
                    Text(text = stringResource(id = R.string.send_ben_unified))
                }

                Spacer(Modifier.size(8.dp))

                Button({ recipientAddressString = WalletFixture.Ben.getAddresses(zcashNetwork).sapling }) {
                    Text(text = stringResource(id = R.string.send_ben_sapling))
                }

                Spacer(Modifier.size(8.dp))

                Button({ recipientAddressString = WalletFixture.Ben.getAddresses(zcashNetwork).transparent }) {
                    Text(text = stringResource(id = R.string.send_ben_transparent))
                }
            }
        }

        Spacer(Modifier.size(8.dp))

        TextField(value = memoString, onValueChange = {
            if (Memo.isWithinMaxLength(it)) {
                memoString = it
            }
        }, label = { Text(stringResource(id = R.string.send_memo)) })

        Spacer(Modifier.fillMaxHeight(MINIMAL_WEIGHT))

        if (validation.isNotEmpty()) {
            /*
             * Note: this is not localized in that it uses the enum constant name and joins the string
             * without regard for RTL.  This will get resolved once we do proper validation for
             * the fields.
             */
            Text(validation.joinToString(", "))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val zecSendValidation =
                    ZecSendExt.new(
                        context,
                        recipientAddressString,
                        amountZecString,
                        memoString,
                    )

                when (zecSendValidation) {
                    is ZecSendExt.ZecSendValidation.Valid -> onGetProposal(zecSendValidation.zecSend)
                    is ZecSendExt.ZecSendValidation.Invalid -> validation = zecSendValidation.validationErrors
                }
            },
            // Needs actual validation
            enabled = amountZecString.isNotBlank() && recipientAddressString.isNotBlank()
        ) {
            Text(stringResource(id = R.string.send_proposal_button))
        }

        if (sendTransactionProposal != null) {
            Text(stringResource(id = R.string.send_proposal_status, sendTransactionProposal.toPrettyString()))

            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                val zecSendValidation =
                    ZecSendExt.new(
                        context,
                        recipientAddressString,
                        amountZecString,
                        memoString,
                    )

                when (zecSendValidation) {
                    is ZecSendExt.ZecSendValidation.Valid -> onSend(zecSendValidation.zecSend)
                    is ZecSendExt.ZecSendValidation.Invalid -> validation = zecSendValidation.validationErrors
                }
            },
            // Needs actual validation
            enabled = amountZecString.isNotBlank() && recipientAddressString.isNotBlank()
        ) {
            Text(stringResource(id = R.string.send_button))
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // ZIP 321 URI examples for Alice's addresses:
        //
        // A valid payment request for a payment of 1 ZEC to a single shielded Sapling address, with a
        // base64url-encoded memo and a message for display by the wallet:
        // zcash:zs15tzaulx5weua5c7l47l4pku2pw9fzwvvnsp4y80jdpul0y3nwn5zp7tmkcclqaca3mdjqjkl7hx?amount=0.0001
        // &memo=VGhpcyBpcyBhIHNpbXBsZSBtZW1vLg&message=Thank%20you%20for%20your%20purchase
        //
        // A valid payment request with one transparent and one shielded Sapling recipient address, with a
        // base64url-encoded Unicode memo for the shielded recipient:
        // zcash:?address=t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi&amount=0.0001
        // &address.1=zs15tzaulx5weua5c7l47l4pku2pw9fzwvvnsp4y80jdpul0y3nwn5zp7tmkcclqaca3mdjqjkl7hx
        // &amount.1=0.0002&memo.1=VGhpcyBpcyBhIHVuaWNvZGUgbWVtbyDinKjwn6aE8J-PhvCfjok

        TextField(
            value = zip321String,
            onValueChange = { zip321String = it },
            label = { Text(stringResource(id = R.string.send_zip_321_uri)) }
        )

        if (sendTransactionProposalFromUri != null) {
            Text(stringResource(id = R.string.send_proposal_status, sendTransactionProposalFromUri.toPrettyString()))
        }

        Button(
            onClick = {
                onGetProposalFromUri(zip321String)
            },
            enabled = zip321String.isNotBlank()
        ) {
            Text(stringResource(id = R.string.send_proposal_from_uri_button))
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        Text(stringResource(id = R.string.send_status, sendState.toString()))
    }
}
