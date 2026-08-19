package uk.crownmedia.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.crownmedia.tv.ui.CrownMediaApp
import uk.crownmedia.tv.ui.CrownViewModel
import uk.crownmedia.tv.ui.theme.CrownMediaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CrownMediaTheme {
                val viewModel: CrownViewModel = viewModel(
                    factory = CrownViewModel.Factory(applicationContext),
                )
                CrownMediaApp(viewModel = viewModel)
            }
        }
    }
}
