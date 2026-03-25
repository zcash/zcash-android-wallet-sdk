package cash.z.ecc.android.sdk.demoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.demoapp.ui.common.BindCompLocalProvider
import cash.z.ecc.android.sdk.demoapp.ui.screen.home.viewmodel.SecretState
import cash.z.ecc.android.sdk.demoapp.ui.screen.home.viewmodel.WalletViewModel
import cash.z.ecc.android.sdk.demoapp.ui.screen.seed.view.Seed
import cash.z.ecc.android.sdk.demoapp.util.fromResources
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork

class ComposeActivity : ComponentActivity() {
    private val walletViewModel by viewModels<WalletViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BindCompLocalProvider {
                MaterialTheme {
                    Surface {
                        MainContent()
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("ktlint:standard:function-naming")
    private fun MainContent() {
        when (walletViewModel.secretState.collectAsStateWithLifecycle().value) {
            SecretState.Loading -> {
                // In the future, we might consider displaying something different here.
            }

            SecretState.None -> {
                var estimatedBirthdayHeight by remember { mutableStateOf<BlockHeight?>(null) }
                Seed(
                    zcashNetwork = ZcashNetwork.fromResources(applicationContext),
                    onExistingWallet = { walletViewModel.persistExistingWallet(it) },
                    onNewWallet = { walletViewModel.persistNewWallet() },
                    onBirthdayEstimation = { estimatedBirthdayHeight = walletViewModel.estimateBirthday(it) },
                    estimatedHeight = estimatedBirthdayHeight
                )
            }

            is SecretState.Ready -> {
                Navigation()
            }
        }
    }
}
