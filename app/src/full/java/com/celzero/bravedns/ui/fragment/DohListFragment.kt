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

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DohEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDohListBinding
import com.celzero.bravedns.ui.activity.ConfigureOtherDnsActivity
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DohListFragment : Fragment(R.layout.fragment_doh_list) {
    private val b by viewBinding(FragmentDohListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val viewModel: DoHEndpointViewModel by viewModel()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var adapter: DohEndpointAdapter? = null

    companion object {
        fun newInstance() = DohListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        observeDnsList()
    }

    private fun observeDnsList() {
        viewModel.dohEndpointList.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                adapter?.submitData(it)
            }
        }
    }

    private fun initView() {
        adapter = DohEndpointAdapter(requireContext(), appConfig)
        b.recyclerDohConnections.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager
        b.recyclerDohConnections.adapter = adapter

        b.dohFabAddServerIcon.setOnClickListener {
            val intent = ConfigureOtherDnsActivity.getIntent(
                requireContext(),
                ConfigureOtherDnsActivity.DnsScreen.DOH.index
            )
            startActivity(intent)
        }
    }
}
