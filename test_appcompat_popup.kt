import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.view.Gravity

fun showLeftMenu(context: Context, toolbar: Toolbar) {
    val popup = PopupMenu(context, toolbar, Gravity.START)
    val menu = toolbar.menu
    for (i in 0 until menu.size()) {
        val item = menu.getItem(i)
        val titleStr = item.title?.toString()?.lowercase() ?: ""
        if (titleStr.contains("kamera") || titleStr.contains("camera") ||
            titleStr.contains("obrolan") || titleStr.contains("chat") ||
            titleStr.contains("cari") || titleStr.contains("search") ||
            titleStr.contains("panggilan") || titleStr.contains("call") ||
            titleStr.contains("telepon") || titleStr.contains("phone") ||
            titleStr.contains("opsi") || titleStr.contains("lainnya") || titleStr.contains("more")
        ) {
            continue
        }
        if (item.isVisible) {
            popup.menu.add(item.groupId, item.itemId, item.order, item.title)
        }
    }
    popup.setOnMenuItemClickListener { menuItem ->
        toolbar.menu.performIdentifierAction(menuItem.itemId, 0)
        true
    }
    popup.show()
}
