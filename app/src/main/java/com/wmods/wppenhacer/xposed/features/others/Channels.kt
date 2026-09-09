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

    private fun isStatusItem(view: View): Boolean {
        val statusTileId = Utils.getID("status_tile_layout", "id")
        val statusPreviewId = Utils.getID("status_preview", "id")
        val statusListId = Utils.getID("status_list", "id")
        val statusRowId = Utils.getID("status_row_container", "id")

        if ((statusTileId != 0 && view.findViewById<View>(statusTileId) != null) ||
            (statusPreviewId != 0 && view.findViewById<View>(statusPreviewId) != null) ||
            (statusListId != 0 && view.findViewById<View>(statusListId) != null) ||
            (statusRowId != 0 && view.findViewById<View>(statusRowId) != null)) {
            return true
        }

        val allText = extractAllText(view).lowercase()
        return allText.contains("tambah status") || allText.contains("status saya") ||
               allText.contains("my status") || allText.contains("add status") ||
               allText.contains("pembaruan terkini") || allText.contains("pembaruan yang dilihat") ||
               allText.contains("recent updates") || allText.contains("viewed updates") ||
               allText.contains("muted updates") || allText.contains("pembaruan yang dibisukan")
    }

    private fun isChannelRelatedView(view: View, channels: Boolean, removechannelRec: Boolean): Boolean {
        if (!channels && !removechannelRec) return false

        // 1. NEVER hide Status section / Stories Carousel / Status rows
        if (isStatusItem(view)) {
            return false
        }

        val allText = extractAllText(view).lowercase()

        // 2. Channel Header ("Saluran", "Channels", "Jelajahi", "Explore", "addon_button")
        val headerTvId = Utils.getID("header_textview", "id")
        val headerTv = if (headerTvId != 0) view.findViewById<TextView>(headerTvId) else null
        val headerText = headerTv?.text?.toString()?.lowercase() ?: ""

        if (headerText.contains("saluran") || headerText.contains("channel") ||
            allText.contains("jelajahi") || allText.contains("explore")) {
            return channels
        }

        val addonBtnId = Utils.getID("addon_button", "id")
        if (addonBtnId != 0 && view.findViewById<View>(addonBtnId) != null) {
            if (channels) return true
        }

        // 3. Directory / Recommendations ("Temukan saluran", "Find channels", "Rekomendasi")
        if (allText.contains("temukan saluran") || allText.contains("find channel") ||
            allText.contains("rekomendasi saluran") || allText.contains("saluran yang disarankan")) {
            return channels || removechannelRec
        }

        // 4. Channel Item Rows (conversations_row_content in updates list that is not a status)
        val convRowId = Utils.getID("conversations_row_content", "id")
        val contactRowId = Utils.getID("contact_row_container", "id")
        val convContactNameId = Utils.getID("conversations_row_contact_name", "id")

        if ((convRowId != 0 && view.findViewById<View>(convRowId) != null) ||
            (contactRowId != 0 && view.findViewById<View>(contactRowId) != null) ||
            (convContactNameId != 0 && view.findViewById<View>(convContactNameId) != null)) {
            return channels
        }

        return false
    }

    private fun extractAllText(view: View): String {
        val sb = StringBuilder()
        if (view is TextView) {
            sb.append(view.text).append(" ")
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                sb.append(extractAllText(view.getChildAt(i))).append(" ")
            }
        }
        return sb.toString()
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
                        val updatesListId = Utils.getID("updates_list", "id")
                        val statusListId = Utils.getID("status_list", "id")
                        val rv = (if (updatesListId != 0) root.findViewById<RecyclerView>(updatesListId) else null)
                            ?: (if (statusListId != 0) root.findViewById<RecyclerView>(statusListId) else null)

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
                        val updatesListId = Utils.getID("updates_list", "id")
                        val statusListId = Utils.getID("status_list", "id")
                        val rv = (if (updatesListId != 0) root.findViewById<RecyclerView>(updatesListId) else null)
                            ?: (if (statusListId != 0) root.findViewById<RecyclerView>(statusListId) else null)

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
                    val updatesListId = Utils.getID("updates_list", "id")
                    val statusListId = Utils.getID("status_list", "id")
                    if ((updatesListId != 0 && id == updatesListId) || (statusListId != 0 && id == statusListId)) {
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
                        val id = Utils.getID("menuitem_create_newsletter", "id")
                        if (id != 0) {
                            menu.findItem(id)?.isVisible = false
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