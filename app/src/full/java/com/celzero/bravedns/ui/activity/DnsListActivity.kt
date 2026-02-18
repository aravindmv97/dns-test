/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class DnsListActivity : AppCompatActivity(R.layout.activity_other_dns_list) {
    private val b by viewBinding(ActivityOtherDnsListBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupClickListeners() {
        b.cardDoh.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOH.index
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resetUi()
        updateSelectedStatus()
    }

    private fun resetUi() {
        b.cardDoh.strokeWidth = 0
        b.initialDoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrDoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
    }

    private fun updateSelectedStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            // always use the id as Dnsx.Preffered as it is the primary dns id for now
            val id = if (appConfig.isSmartDnsEnabled()) {
                Backend.Plus
            } else {
                Backend.Preferred
            }
            val state = VpnController.getDnsStatus(id)
            val working =
                if (state == null) {
                    false
                } else {
                    when (Transaction.Status.fromId(state)) {
                        Transaction.Status.COMPLETE,
                        Transaction.Status.START -> {
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            withContext(Dispatchers.Main) { highlightSelectedUi(working) }
        }
    }

    private fun highlightSelectedUi(working: Boolean) {
        val strokeColor: Int
        val textColor: Int
        if (working) {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
            textColor = fetchColor(this, R.attr.secondaryTextColor)
        } else {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentBad)
            textColor = fetchColor(this, R.attr.accentBad)
        }

        if (appConfig.getDnsType() == AppConfig.DnsType.DOH) {
            b.cardDoh.strokeColor = strokeColor
            b.cardDoh.strokeWidth = 2
            b.initialDoh.setTextColor(textColor)
            b.abbrDoh.setTextColor(textColor)
        }
    }
}
