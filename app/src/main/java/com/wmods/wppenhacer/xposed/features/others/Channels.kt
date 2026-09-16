package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap

class Channels(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    private val originalDimensions = WeakHashMap<View, Pair<Int, Int>>()
    private val hookedAdapterClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val attachedRecyclerViews = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())

    private val statusTileId by lazy { Utils.getID("status_tile_layout", "id") }
    private val statusPreviewId by lazy { Utils.getID("status_preview", "id") }
    private val statusListId by lazy { Utils.getID("status_list", "id") }
    private val statusRowId by lazy { Utils.getID("status_row_container", "id") }
    private val headerTvId by lazy { Utils.getID("header_textview", "id") }
    private val addonBtnId by lazy { Utils.getID("addon_button", "id") }
    private val convRowId by lazy { Utils.getID("conversations_row_content", "id") }
    private val contactRowId by lazy { Utils.getID("contact_row_container", "id") }
    private val convContactNameId by lazy { Utils.getID("conversations_row_contact_name", "id") }
    private val updatesListId by lazy { Utils.getID("updates_list", "id") }
    private val createNewsletterId by lazy { Utils.getID("menuitem_create_newsletter", "id") }

    private val statusKeywords = arrayOf(
        "tambah status", "status saya", "my status", "add status",
        "pembaruan terkini", "pembaruan yang dilihat", "recent updates",
        "viewed updates", "muted updates", "pembaruan yang dibisukan"
    )

    private val channelKeywords = arrayOf(
        "saluran", "channel", "jelajahi", "explore"
    )

    private val directoryKeywords = arrayOf(
        "temukan saluran", "find channel", "rekomendasi saluran", "saluran yang disarankan"
    )

    private fun isStatusItem(view: View): Boolean {
        if ((statusTileId > 0 && view.findViewById<View>(statusTileId) != null) ||
            (statusPreviewId > 0 && view.findViewById<View>(statusPreviewId) != null) ||
            (statusListId > 0 && view.findViewById<View>(statusListId) != null) ||
            (statusRowId > 0 && view.findViewById<View>(statusRowId) != null)) {
            return true
        }

        return containsAnyText(view, statusKeywords)
    }

    private fun isChannelRelatedView(view: View, channels: Boolean, removechannelRec: Boolean): Boolean {
        if (!channels && !removechannelRec) return false

        // 1. NEVER hide Status section / Stories Carousel / Status rows
        if (isStatusItem(view)) {
            return false
        }

        // 2. Channel Header ("Saluran", "Channels", "Jelajahi", "Explore", "addon_button")
        val headerTv = if (headerTvId > 0) view.findViewById<TextView>(headerTvId) else null
        val headerText = headerTv?.text?.toString()
        if (headerText != null && (headerText.contains("saluran", ignoreCase = true) || headerText.contains("channel", ignoreCase = true))) {
            return channels
        }

        if (addonBtnId > 0 && view.findViewById<View>(addonBtnId) != null) {
            if (channels) return true
        }

        // 3. Directory / Recommendations ("Temukan saluran", "Find channels", "Rekomendasi")
        if (containsAnyText(view, directoryKeywords)) {
            return channels || removechannelRec
        }

        // 4. Channel Item Rows (conversations_row_content in updates list that is not a status)
        if ((convRowId > 0 && view.findViewById<View>(convRowId) != null) ||
            (contactRowId > 0 && view.findViewById<View>(contactRowId) != null) ||
            (convContactNameId > 0 && view.findViewById<View>(convContactNameId) != null)) {
            return channels
        }

        if (containsAnyText(view, channelKeywords)) {
            return channels
        }

        return false
    }

    private fun containsAnyText(view: View, keywords: Array<String>, maxDepth: Int = 4): Boolean {
        if (maxDepth < 0) return false
        if (view is TextView) {
            val text = view.text?.toString() ?: return false
            for (kw in keywords) {
                if (text.contains(kw, ignoreCase = true)) {
                    return true
                }
            }
            return false
        } else if (view is ViewGroup) {
            val count = view.childCount
            for (i in 0 until count) {
                if (containsAnyText(view.getChildAt(i), keywords, maxDepth - 1)) {
                    return true
                }
            }
        }
        return false
    }

    private fun collapseView(view: View) {
        val lp = view.layoutParams ?: return
        if (!originalDimensions.containsKey(view)) {
            originalDimensions[view] = Pair(lp.width, lp.height)
        }
        lp.width = 0
        lp.height = 0
        view.layoutParams = lp
        view.visibility = View.GONE
    }

    private fun restoreView(view: View) {
        val original = originalDimensions[view] ?: return
        val lp = view.layoutParams
        if (lp != null) {
            lp.width = original.first
            lp.height = original.second
            view.layoutParams = lp
        }
        view.visibility = View.VISIBLE
    }

    private fun attachRecyclerViewFilter(recyclerView: RecyclerView) {
        val curChannels = prefs.getBoolean("channels", false)
        val curRemoveRec = prefs.getBoolean("removechannel_rec", false)

        // 1. Filter existing children
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            if (isChannelRelatedView(child, curChannels, curRemoveRec)) {
                collapseView(child)
            } else {
                restoreView(child)
            }
        }

        // 2. Guard listener registration to prevent stacking listeners
        if (attachedRecyclerViews.add(recyclerView)) {
            recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val nowChannels = prefs.getBoolean("channels", false)
                    val nowRemoveRec = prefs.getBoolean("removechannel_rec", false)
                    if (isChannelRelatedView(view, nowChannels, nowRemoveRec)) {
                        collapseView(view)
                    } else {
                        restoreView(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    restoreView(view)
                }
            })
        }

        // 3. Filter on adapter changes & bind
        recyclerView.adapter?.let { adapter ->
            hookAdapter(adapter)
        }
    }

    private fun hookAdapter(adapter: RecyclerView.Adapter<*>) {
        val adapterClass = adapter.javaClass
        if (!hookedAdapterClasses.add(adapterClass)) return

        try {
            XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val holder = param.args.firstOrNull() as? RecyclerView.ViewHolder ?: return
                    val nowChannels = prefs.getBoolean("channels", false)
                    val nowRemoveRec = prefs.getBoolean("removechannel_rec", false)
                    if (isChannelRelatedView(holder.itemView, nowChannels, nowRemoveRec)) {
                        collapseView(holder.itemView)
                    } else {
                        restoreView(holder.itemView)
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    override fun doHook() {
        val updatesFragmentClass = runCatching {
            classLoader.loadClass("com.whatsapp.status.updates.ui.UpdatesFragment")
        }.getOrNull() ?: runCatching {
            classLoader.loadClass("com.whatsapp.updates.ui.UpdatesFragment")
        }.getOrNull() ?: runCatching {
            Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "UpdatesFragment")
        }.getOrNull()

        XposedBridge.log("[WaEnhancer] Channels: found updatesFragmentClass = ${updatesFragmentClass?.name}")

        if (updatesFragmentClass != null) {
            XposedBridge.hookAllMethods(updatesFragmentClass, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.args.firstOrNull() as? View ?: return
                    root.post {
                        val rv = (if (updatesListId > 0) root.findViewById<RecyclerView>(updatesListId) else null)
                            ?: (if (statusListId > 0) root.findViewById<RecyclerView>(statusListId) else null)

                        if (rv != null) {
                            attachRecyclerViewFilter(rv)
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(updatesFragmentClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject
                    val root = de.robv.android.xposed.XposedHelpers.callMethod(fragment, "getView") as? View ?: return
                    root.post {
                        val rv = (if (updatesListId > 0) root.findViewById<RecyclerView>(updatesListId) else null)
                            ?: (if (statusListId > 0) root.findViewById<RecyclerView>(statusListId) else null)

                        if (rv != null) {
                            attachRecyclerViewFilter(rv)
                        }
                    }
                }
            })
        }

        // Hook RecyclerView.setAdapter strictly for updates_list or status_list
        try {
            XposedBridge.hookAllMethods(RecyclerView::class.java, "setAdapter", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter = param.args.firstOrNull() as? RecyclerView.Adapter<*> ?: return
                    val rv = param.thisObject as? RecyclerView ?: return
                    val id = rv.id
                    if ((updatesListId > 0 && id == updatesListId) || (statusListId > 0 && id == statusListId)) {
                        hookAdapter(adapter)
                        attachRecyclerViewFilter(rv)
                    }
                }
            })
        } catch (_: Throwable) {}

        // Hook menu to hide "Create Newsletter"
        try {
            XposedBridge.hookAllMethods(WppCore.homeActivityClass, "onPrepareOptionsMenu", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val channels = prefs.getBoolean("channels", false)
                    if (channels) {
                        val menu = param.args.firstOrNull() as? Menu ?: return
                        if (createNewsletterId > 0) {
                            menu.findItem(createNewsletterId)?.isVisible = false
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    override fun getPluginName(): String {
        return "Channels"
    }
}