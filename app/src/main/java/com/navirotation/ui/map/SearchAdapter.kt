package com.navirotation.ui.map

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.navirotation.data.map.SearchPlaceResponse.Document
import com.navirotation.databinding.ItemSearchBinding

class SearchAdapter : RecyclerView.Adapter<SearchAdapter.ViewHolder>() {
    private var itemList: ArrayList<Document> = ArrayList()
    private var itemClickListener: ItemClickListener? = null

    fun setOnItemClickListener(itemClickListener: ItemClickListener){
        this.itemClickListener = itemClickListener
    }
    inner class ViewHolder(private val binding: ItemSearchBinding): RecyclerView.ViewHolder(binding.root) {
        fun bind(document: Document) {
            val distance = document.distance

            if(distance.isNotEmpty() && !distance.contains("km") && distance.isNotBlank()) {
                val count = distance.toLong()
                document.distance = "${(count/ 1000)}.${(count % 1000 / 100)}km"
            }

            binding.searchItem = document

            if(absoluteAdapterPosition != RecyclerView.NO_POSITION) {
                itemView.setOnClickListener {
                    itemClickListener?.onItemClickListener(itemView, document, absoluteAdapterPosition)
                }
            }
        }
    }

    fun setItem(pItemList: ArrayList<Document>) {
        itemList = pItemList
        notifyItemRangeChanged(0, itemList.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return itemList.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(itemList[position])
    }
}

interface ItemClickListener {
    fun onItemClickListener(v: View, data: Document, pos: Int)
}