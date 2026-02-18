/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val workScheduler by inject<WorkScheduler>()
    private val doHEndpointRepository by inject<DoHEndpointRepository>()

    private var isVpnActivated: Boolean = false

    private lateinit var startForResult: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        registerForActivityResult()
        initializeClickListeners()
        observeVpnState()
    }

    private fun initializeClickListeners() {
        b.fhsDnsOnOffBtn.setOnClickListener {
            handleMainScreenBtnClickEvent()
        }
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) {
            isVpnActivated = it
            updateMainButtonUi()
        }

        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (VpnController.isAppPaused()) return@observe
            syncDnsStatus()
        }
    }

    private fun updateMainButtonUi() {
        // Requirement: Always make the button enabled
        b.fhsDnsOnOffBtn.text = getString(R.string.hsf_start_btn_state)
        b.fhsDnsOnOffBtn.isEnabled = true
    }

    private fun handleMainScreenBtnClickEvent() {
        // Requirement: Set the NextDNS and Start the VPN.
        // If the connection is already established, on clicking again, let it again connect.
        io {
            val nextDnsUrl = "https://NEXT_DNS_KEY.dns.nextdns.io/"
            val endpoint = doHEndpointRepository.getByUrl(nextDnsUrl)
            if (endpoint != null) {
                appConfig.handleDoHChanges(endpoint)
            }
            // Start (or restart) VPN
            VpnController.start(requireContext(), true)
        }
    }

    private fun prepareAndStartVpn() {
        if (prepareVpnService()) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        getNotificationPermissionIfNeeded()
        VpnController.start(requireContext(), true)
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!isAtleastT()) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? =
            try {
                VpnService.prepare(requireContext())
            } catch (e: NullPointerException) {
                Logger.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
                return false
            }
        if (prepareVpnIntent != null) {
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.hsf_vpn_dialog_header)
        builder.setMessage(R.string.hsf_vpn_dialog_message)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            try {
                startForResult.launch(prepareVpnIntent)
            } catch (e: ActivityNotFoundException) {
                Logger.e(LOG_TAG_VPN, "Activity not found to start VPN service", e)
            }
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK) {
                    startVpnService()
                }
            }

        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                persistentState.shouldRequestNotificationPermission = isGranted
            }
    }

    @Suppress("DEPRECATION")
    private fun syncDnsStatus() {
        val vpnState = VpnController.state()
        val statusText: String
        val nextDnsStatusText: String
        
        if (!vpnState.on) {
            statusText = "No Internet!"
            nextDnsStatusText = ""
        } else {
            statusText = when (vpnState.connectionState) {
                BraveVPNService.State.WORKING -> "Internet Connected!"
                BraveVPNService.State.NEW, null -> "Connecting.."
                else -> "No Internet!"
            }
            
            // Requirement: Always display the NextDNS status and the ip below the status label
            val nextDnsUrl = "https://NEXT_DNS_KEY.dns.nextdns.io/"
            val ip = "NEXT_DNS_KEY.dns.nextdns.io" // extracted from URL for display
            
            val isNextDns = vpnState.serverName?.contains(ip, true) == true
            
            nextDnsStatusText = if (isNextDns && vpnState.connectionState == BraveVPNService.State.WORKING) {
                "NextDNS Active: $ip"
            } else if (vpnState.connectionState != BraveVPNService.State.WORKING && vpnState.connectionState != BraveVPNService.State.NEW && vpnState.connectionState != null) {
                // Requirement: If there occurs any error related to NextDNS, display it below the status label
                "NextDNS Error: ${vpnState.connectionState?.name}"
            } else {
                "NextDNS: $ip"
            }
        }
        
        b.fhsProtectionLevelTxt.text = statusText
        b.fhsProtectionLevelTxt.visibility = View.VISIBLE
        
        b.fhsNextdnsStatusTxt.text = nextDnsStatusText
        b.fhsNextdnsStatusTxt.visibility = if (nextDnsStatusText.isEmpty()) View.GONE else View.VISIBLE
        
        updateMainButtonUi()
    }

    override fun onResume() {
        super.onResume()
        if (!VpnController.hasTunnel()) {
            prepareAndStartVpn()
        }
        syncDnsStatus()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
