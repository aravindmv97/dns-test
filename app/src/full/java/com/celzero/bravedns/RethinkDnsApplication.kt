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
package com.celzero.bravedns

import Logger
import Logger.LOG_TAG_SCHEDULER
import Logger.LOG_TAG_VPN
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class RethinkDnsApplication : Application() {
    companion object {
        var DEBUG: Boolean = false
    }

    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()

    override fun onCreate() {
        super.onCreate()
        DEBUG =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE ==
                    ApplicationInfo.FLAG_DEBUGGABLE

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }

        // Initialize global exception handler
        GlobalExceptionHandler.initialize(this)
        FirebaseErrorReporting.initialize()

        turnOnStrictMode()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            scheduleJobs()
            setupNextDnsOnly()
            VpnController.start(this@RethinkDnsApplication)
        }

        startVpnMonitor()
    }

    private suspend fun setupNextDnsOnly() {
        val nextDnsUrl = "https://NEXT_DNS_KEY.dns.nextdns.io/"
        
        // Remove all existing DoH endpoints
        doHEndpointRepository.clearAllData()
        
        // Add NextDNS
        val nextDns = DoHEndpoint(-1, "NextDNS", nextDnsUrl, "", true, true, true, System.currentTimeMillis(), 0)
        doHEndpointRepository.insert(nextDns)
        
        // Set as default
        persistentState.dnsType = AppConfig.DnsType.DOH.type
        persistentState.connectedDnsName = "NextDNS,$nextDnsUrl"
    }

    private fun startVpnMonitor() {
        val handler = Handler(Looper.getMainLooper())
        val monitorRunnable = object : Runnable {
            override fun run() {
                if (!VpnController.hasTunnel()) {
                    Logger.i(LOG_TAG_VPN, "VPN monitor: VPN is off, starting...")
                    VpnController.start(this@RethinkDnsApplication)
                }
                handler.postDelayed(this, 10000) // 10 seconds
            }
        }
        handler.post(monitorRunnable)
    }

    private suspend fun scheduleJobs() {
        Logger.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        // database refresh is used in both headless and main project
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().scheduleDataUsageJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()
        get<WorkScheduler>().schedulePurgeConsoleLogs()
    }

    private fun turnOnStrictMode() {
        if (!DEBUG) return
        // Uncomment the code below to enable the StrictModes.
        // To test the apps disk read/writes, network usages.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .permitDiskReads()
                .permitDiskWrites()
                .permitNetwork()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }
}
