package com.galaxytvstick.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Category
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.EpgRepository
import com.galaxytvstick.app.databinding.ItemCategoryBinding
import com.galaxytvstick.app.databinding.ItemChannelBinding

// ---------------------------------------------------------------- Kategori

class CategoryAdapter(
    private val onSelect: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<Category> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var selectedId: String? = null
        set(value) { field = value; notifyDataSetChanged() }

    class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.b.name.text = c.name
        h.b.root.isSelected = c.id == selectedId
        h.b.root.setOnClickListener { onSelect(c) }
    }
}

// ---------------------------------------------------------------- Chanèl

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
    private val onFocus: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var favorites: Set<Int> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }
    /** Chanèl k ap jwe kounye a (make l nan lis la). */
    var playingId: Int = -1
        set(value) { field = value; notifyDataSetChanged() }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.b.number.text = ch.num.toString()
        h.b.name.text = ch.name
        h.b.fav.visibility = if (ch.streamId in favorites) View.VISIBLE else View.GONE
        h.b.root.isActivated = ch.streamId == playingId
        h.b.logo.load(ch.icon) {
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
        }

        // Pwogram k ap pase kounye a (EPG)
        val now = EpgRepository.nowFor(ch)
        if (now != null) {
            h.b.epgNow.visibility = View.VISIBLE
            h.b.epgProgress.visibility = View.VISIBLE
            h.b.epgNow.text = now.title
            h.b.epgProgress.progress = now.progress()
        } else {
            h.b.epgNow.visibility = View.GONE
            h.b.epgProgress.visibility = View.GONE
        }

        h.b.root.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.b.root.setOnLongClickListener { onLongClick(ch); true }
        h.b.root.setOnFocusChangeListener { _, has -> if (has) onFocus(ch) }
    }
}
