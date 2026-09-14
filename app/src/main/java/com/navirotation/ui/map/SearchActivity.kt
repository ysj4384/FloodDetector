package com.navirotation.ui.map

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.navirotation.R
import com.navirotation.base.BaseActivity
import com.navirotation.data.map.SearchPlaceResponse.Document
import com.navirotation.databinding.ActivitySearchBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchActivity: BaseActivity() {
    private lateinit var binding: ActivitySearchBinding
    private lateinit var viewModel: SearchViewModel
    private var adapter = SearchAdapter()

    private var myLatitude = ""
    private var myLongitude = ""
    private var searchText = ""
    private var searchList = arrayListOf<Document>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(NAVI_ROTATION, "onCreate()")

        binding = DataBindingUtil.setContentView(this, R.layout.activity_search)
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        binding.rvSearch.adapter = adapter

        myLatitude = intent.getStringExtra("latitude").toString()
        myLongitude = intent.getStringExtra("longitude").toString()

        observeFlow()
        onClickListener()
    }

    private fun onClickListener() {
        binding.etSearch.setOnEditorActionListener { textView, action, _ ->
            var handled = false
            if(action == EditorInfo.IME_ACTION_SEARCH) {
                if(textView.text.isNullOrEmpty() || textView.text.isNullOrBlank()) {
                    return@setOnEditorActionListener false
                }

                searchText = textView.text.toString()

                // 카카오 장소 검색 API 호출
                viewModel.getSearchPlaceData(textView.text.toString() ,myLongitude, myLatitude)

                // 키보드 닫기
                val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                handled = true
            }
            handled
        }

        adapter.setOnItemClickListener(object : ItemClickListener{
            override fun onItemClickListener(v: View, data: Document, pos: Int) {
                val intent = Intent()
                intent.putExtra("placeName", data.placeName)
                intent.putExtra("addressName", data.addressName)
                intent.putExtra("distance", data.distance)
                intent.putExtra("latitude", data.y.toDouble())
                intent.putExtra("longitude", data.x.toDouble())
                setResult(RESULT_OK, intent)
                finish()
            }
        })
    }

    private fun observeFlow() {
        lifecycleScope.launch {
            viewModel.searchPlaceData.collectLatest {
                Log.d(NAVI_ROTATION, "searchPlaceData: $it")

                // todo : 에러 핸들링

                if(it.success == null ) return@collectLatest

                val meta = it.success.meta
                val documents = it.success.documents

                if(meta.totalCount == 0) {
                    binding.tvEmptyList.setText(R.string.empty_search_list)
                    binding.tvEmptyList.visibility = View.VISIBLE
                } else {
                    binding.tvEmptyList.visibility = View.GONE
                    if(meta.totalCount == 1) {
                        searchList.add(documents[0])
                    }else {
                        searchList = documents as ArrayList<Document>
                    }
                }

                adapter.setItem(searchList)
            }
        }
    }
}