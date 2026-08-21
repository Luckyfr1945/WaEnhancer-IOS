import android.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import android.view.MenuItem
import android.content.Context

fun showLeftMenu(context: Context, toolbar: Toolbar, anchor: android.view.View) {
    val popup = PopupMenu(context, anchor)
    for (i in 0 until toolbar.menu.size()) {
        val item = toolbar.menu.getItem(i)
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
