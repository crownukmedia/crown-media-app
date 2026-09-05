package uk.crownmedia.app

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.text.TextUtils
import android.content.res.Resources
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
import coil.dispose
import coil.request.CachePolicy
import coil.size.Precision
import uk.crownmedia.app.databinding.ItemCategoryBinding
import uk.crownmedia.app.databinding.ItemContentBinding
import uk.crownmedia.data.xtream.XtreamCategory
import java.net.URI

data class CatalogCard(
    val id: String,
    val kind: String,
    val title: String,
    val imageUrl: String?,
    val meta: String,
    val badge: String = "",
    val extension: String? = null,
    val categoryId: String = "",
    val localArtwork: Int? = null,
    val healthHint: Int = 0,
    val isAdult: Boolean = false,
)

internal fun CatalogCard.preferredArtworkSource(): Any {
    val remote = imageUrl?.trim()?.takeIf { value ->
        runCatching { URI(value) }.getOrNull()?.let { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        } == true
    }
    return when {
        kind == "live" && remote != null -> remote
        localArtwork != null -> localArtwork
        remote != null -> remote
        else -> R.drawable.crown_media_logo_header
    }
}

internal fun categoryAccessibilityLabel(
    resources: Resources,
    category: XtreamCategory,
    count: Int?,
): String = if (count != null) {
    resources.getQuantityString(
        R.plurals.category_item_count_description,
        count,
        category.name,
        count,
    )
} else category.name

class CategoryAdapter(
    private val onClick: (XtreamCategory) -> Unit,
    private val onLongClick: (XtreamCategory) -> Unit,
    private val onDpad: (View, Int, Int, KeyEvent) -> Boolean = { _, _, _, _ -> false },
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
    data class Row(val category: XtreamCategory, val selected: Boolean)
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.category.id == newItem.category.id
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    })
    private var counts: Map<String, Int> = emptyMap()

    fun submit(values: List<XtreamCategory>, selectedId: String = "all", committed: (() -> Unit)? = null) {
        differ.submitList(values.map { Row(it, it.id == selectedId) }, committed)
    }

    /** TV-003/TV-004: apply per-category item counts without resubmitting the list (keeps focus/scroll). */
    fun updateCounts(values: Map<String, Int>) {
        if (counts == values) return
        val previous = counts
        counts = values
        differ.currentList.indices
            .filter { index -> previous[differ.currentList[index].category.id] != values[differ.currentList[index].category.id] }
            .forEach(::notifyItemChanged)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = differ.currentList.size
    fun positionOf(categoryId: String): Int = differ.currentList.indexOfFirst { it.category.id == categoryId }
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(differ.currentList[position])

    inner class Holder(private val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            val value = row.category
            binding.categoryName.text = value.name
            binding.categoryName.ellipsize = TextUtils.TruncateAt.END
            binding.categoryName.maxLines = 1
            binding.categoryName.maxWidth = (240 * binding.root.resources.displayMetrics.density).toInt()
            binding.categoryName.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            val count = counts[value.id]?.takeIf {
                it > 0 && value.id != "all" && value.id != "favorites" && !value.id.startsWith("season:")
            }
            binding.categoryCount.isVisible = count != null
            if (count != null) binding.categoryCount.text = "($count)"
            binding.root.isSelected = row.selected
            binding.root.contentDescription = categoryAccessibilityLabel(binding.root.resources, value, count)
            binding.root.nextFocusLeftId = if (
                binding.root.context.appLayout() != AppLayout.TELEVISION && bindingAdapterPosition == 0
            ) R.id.category_menu_button else View.NO_ID
            binding.categoryActions.isVisible = binding.root.context.appLayout() != AppLayout.TELEVISION && value.id != "all" && value.id != "favorites" && !value.id.startsWith("season:")
            binding.categoryActions.contentDescription = binding.root.context.getString(R.string.options_for, value.name)
            binding.categoryActions.setOnClickListener { onLongClick(value) }
            binding.root.setOnClickListener { onClick(value) }
            binding.root.setOnLongClickListener { onLongClick(value); true }
            binding.root.setOnKeyListener { view, keyCode, event ->
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && onDpad(view, position, keyCode, event)) {
                    true
                } else if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && value.id !in setOf("all", "favorites") && !value.id.startsWith("season:")) {
                    onLongClick(value)
                    true
                } else false
            }
            binding.root.setOnFocusChangeListener { view, focused -> view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start() }
        }
    }
}

class CatalogAdapter(
    private val onClick: (CatalogCard) -> Unit,
    private val onLongClick: (CatalogCard) -> Unit,
    private val onDpad: (View, Int, Int, KeyEvent) -> Boolean = { _, _, _, _ -> false },
) : RecyclerView.Adapter<CatalogAdapter.Holder>() {
    private var uniformLandscapeCards = false
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<CatalogCard>() {
        override fun areItemsTheSame(oldItem: CatalogCard, newItem: CatalogCard) = oldItem.id == newItem.id && oldItem.kind == newItem.kind
        override fun areContentsTheSame(oldItem: CatalogCard, newItem: CatalogCard) = oldItem == newItem
    })
    val currentItems: List<CatalogCard> get() = differ.currentList
    fun itemAt(position: Int): CatalogCard? = differ.currentList.getOrNull(position)
    fun setUniformLandscapeCards(enabled: Boolean) {
        if (uniformLandscapeCards == enabled) return
        uniformLandscapeCards = enabled
        notifyItemRangeChanged(0, itemCount)
    }
    fun submit(values: List<CatalogCard>, committed: (() -> Unit)? = null) = differ.submitList(values, committed)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = differ.currentList.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(differ.currentList[position])

    inner class Holder(private val binding: ItemContentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(value: CatalogCard) {
            binding.title.text = value.title
            binding.meta.text = value.meta
            binding.badge.text = value.badge
            binding.badge.visibility = if (value.badge.isBlank()) View.GONE else View.VISIBLE
            val television = binding.root.context.appLayout() == AppLayout.TELEVISION
            val poster = !uniformLandscapeCards && (value.kind == "movie" || value.kind == "series")
            val artworkHeightDp = if (poster) 220 else if (television) 118 else 104
            val artworkHeight = (artworkHeightDp * binding.root.resources.displayMetrics.density).toInt()
            val artworkContainer = binding.artwork.parent as View
            artworkContainer.layoutParams = artworkContainer.layoutParams.apply { height = artworkHeight }
            val artworkDensity = binding.root.resources.displayMetrics.density
            val brandInset = (12 * artworkDensity).toInt()
            val source = value.preferredArtworkSource()
            val brandFallback = source == R.drawable.crown_media_logo_header
            fun showBrandArtwork() {
                binding.artwork.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.artwork.setPadding(brandInset, brandInset, brandInset, brandInset)
                binding.artwork.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.white))
            }
            showBrandArtwork()
            binding.artwork.load(source) {
                size(if (poster) 360 else 480, if (poster) 540 else 270)
                precision(Precision.INEXACT)
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                networkCachePolicy(CachePolicy.ENABLED)
                allowHardware(true)
                crossfade(false)
                placeholder(uk.crownmedia.app.R.drawable.crown_media_logo_header)
                error(uk.crownmedia.app.R.drawable.crown_media_logo_header)
                listener(
                    onSuccess = { _, _ ->
                        if (brandFallback) {
                            showBrandArtwork()
                        } else {
                            binding.artwork.setBackgroundColor(ContextCompat.getColor(binding.root.context, if (value.kind == "live") R.color.white else R.color.crown_surface))
                            binding.artwork.scaleType = when (value.kind) {
                                "live" -> ImageView.ScaleType.CENTER_INSIDE
                                else -> ImageView.ScaleType.CENTER_CROP
                            }
                            val inset = if (value.kind == "live") (10 * artworkDensity).toInt() else 0
                            binding.artwork.setPadding(inset, inset, inset, inset)
                        }
                    },
                    onError = { _, _ -> showBrandArtwork() },
                )
            }
            binding.root.contentDescription = listOf(value.title, value.meta, value.badge).filter { it.isNotBlank() }.joinToString(", ")
            binding.moreActions.isVisible = binding.root.context.appLayout() != AppLayout.TELEVISION && value.kind != "home"
            binding.moreActions.contentDescription = binding.root.context.getString(R.string.options_for, value.title)
            binding.moreActions.setOnClickListener { onLongClick(value) }
            binding.root.setOnClickListener { onClick(value) }
            binding.root.setOnLongClickListener { onLongClick(value); true }
            binding.root.setOnKeyListener { view, keyCode, event ->
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && onDpad(view, position, keyCode, event)) {
                    true
                } else if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && value.kind != "home") {
                    onLongClick(value)
                    true
                } else false
            }
            binding.root.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.055f else 1f).scaleY(if (focused) 1.055f else 1f).translationZ(if (focused) 12f else 0f).setDuration(130).start()
                binding.root.strokeColor = ContextCompat.getColor(view.context, if (focused) R.color.crown_gold else R.color.crown_border)
                val density = view.resources.displayMetrics.density
                binding.root.strokeWidth = ((if (focused) 3 else 1) * density).toInt().coerceAtLeast(1)
            }
        }

        fun recycle() = binding.artwork.dispose()
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }
}
