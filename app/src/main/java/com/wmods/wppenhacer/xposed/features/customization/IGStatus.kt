package com.wmods.wppenhacer.xposed.features.customization

import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import com.wmods.wppenhacer.adapter.IGStatusAdapter
import com.wmods.wppenhacer.views.IGStatusView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import org.luckypray.dexkit.query.enums.StringMatchType
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

// Referências fracas: enquanto a view estiver anexada à lista, o próprio ListView a mantém
// viva. Com uma ArrayList comum, cada recriação da HomeActivity vazava a Activity inteira
// junto com sua árvore de views.
private val mListStatusContainer: MutableSet<IGStatusView> =
    Collections.newSetFromMap(WeakHashMap())

class IGStatus(loader: ClassLoader, preferences:SharedPreferences) : Feature(loader, preferences) {

    companion object {
        @JvmField
        var itens = ArrayList<Any?>()
        @JvmField
        val viewedItens: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

        fun updateStatusLists(lists: List<List<*>>) {
            val newList = ArrayList<Any?>()
            newList.add(null)
            for (i in lists.indices) {
                val list = lists[i]
                val isViewed = (i > 0)
                for (item in list) {
                    if (item != null && !newList.contains(item)) {
                        newList.add(item)
                        if (isViewed) {
                            viewedItens.add(item)
                        } else {
                            viewedItens.remove(item)
                        }
                    }
                }
            }
            itens = newList
        }
    }

    @Throws(Throwable::class)
    override fun doHook() {
        if (!prefs.getBoolean("igstatus", true)) return

        val fabintMethod = Unobfuscator.loadFabMethod(classLoader)

        val archivedFragmentClass = Unobfuscator.findFirstClassUsingName(
            classLoader, StringMatchType.EndsWith, "ArchivedConversationsFragment"
        )
        val folderFragmentClass = Unobfuscator.findFirstClassUsingName(
            classLoader, StringMatchType.EndsWith, "FolderConversationsFragment"
        )
        val lockedFragmentClass = Unobfuscator.findFirstClassUsingName(
            classLoader, StringMatchType.EndsWith, "LockedConversationsFragment"
        )

        val statusInfoClass = Unobfuscator.loadStatusInfoClass(classLoader)
        logDebug(statusInfoClass)

        val getViewConversationMethod = Unobfuscator.loadGetViewConversationMethod(classLoader)
        XposedBridge.hookMethod(getViewConversationMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val targetObj = param.thisObject ?: return
                val className = targetObj.javaClass.name
                if (className.contains("Archived") || className.contains("Locked") || 
                    className.contains("Folder") || className.contains("Hidden")) return

                if (archivedFragmentClass?.isInstance(targetObj) == true) return
                if (folderFragmentClass?.isInstance(targetObj) == true) return
                if (lockedFragmentClass?.isInstance(targetObj) == true) return
                val view = param.result as? ViewGroup ?: return
                val list = view.findViewById<ViewGroup>(android.R.id.list) ?: return
                val act = WppCore.getCurrentActivity() ?: (view.context as? android.app.Activity) ?: return
                val mStatusContainer = IGStatusView(act)
                if (list is ListView) {
                    list.isNestedScrollingEnabled = true
                    val layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, Utils.dipToPixels(88)
                    )
                    mStatusContainer.layoutParams = layoutParams
                    list.addHeaderView(mStatusContainer)
                } else {
                    val paddingTop = list.paddingTop
                    val parentView = (list.parent as? ViewGroup) ?: return
                    val background = list.background
                    mStatusContainer.background = background
                    list.setPadding(0, 0, 0, 0)
                    val layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Utils.dipToPixels(88)
                    )
                    layoutParams.topMargin = paddingTop
                    mStatusContainer.layoutParams = layoutParams
                    parentView.addView(mStatusContainer, 0)
                }
                val id = (try { fabintMethod.invoke(param.thisObject) as? Int } catch (_: Throwable) { null }) ?: 0
                val igStatus = mListStatusContainer.find { it.fragmentId == id }
                if (igStatus != null) {
                    mStatusContainer.adapter = igStatus.adapter
                    mListStatusContainer.remove(igStatus)
                }
                if (mStatusContainer.adapter == null) {
                    val currentAct = WppCore.getCurrentActivity() ?: (view.context as? android.app.Activity)
                    if (currentAct != null) {
                        try {
                            mStatusContainer.adapter = IGStatusAdapter(currentAct, statusInfoClass)
                        } catch (_: Throwable) {}
                    }
                }
                mStatusContainer.fragmentId = id
                mListStatusContainer.add(mStatusContainer)
            }
        })

        val onUpdateStatusChanged = Unobfuscator.loadOnUpdateStatusChanged(classLoader)
        logDebug(Unobfuscator.getMethodDescriptor(onUpdateStatusChanged))

        val updateModel = onUpdateStatusChanged.declaringClass
        logDebug(updateModel)

        XposedBridge.hookAllConstructors(updateModel, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val newList = ArrayList<Any?>()
                newList.add(null)
                for (it in itens) {
                    if (it != null && !newList.contains(it)) {
                        newList.add(it)
                    }
                }
                itens = newList
                val act = WppCore.getCurrentActivity()
                for (mStatusContainer in mListStatusContainer) {
                    if (act != null && mStatusContainer.adapter == null) {
                        try {
                            mStatusContainer.adapter = IGStatusAdapter(act, statusInfoClass)
                        } catch (_: Throwable) {}
                    }
                    mStatusContainer.updateList()
                }
            }
        })

        val onStatusListUpdatesClass = Unobfuscator.loadStatusListUpdatesClass(classLoader)
        logDebug(onStatusListUpdatesClass)

        XposedBridge.hookAllConstructors(onStatusListUpdatesClass, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val lists = param.args.filterIsInstance<List<*>>()
                updateStatusLists(lists)
                val act = WppCore.getCurrentActivity()
                for (mStatusContainer in mListStatusContainer) {
                    if (act != null && mStatusContainer.adapter == null) {
                        try {
                            mStatusContainer.adapter = IGStatusAdapter(act, statusInfoClass)
                        } catch (_: Throwable) {}
                    }
                    mStatusContainer.updateList()
                }
            }
        })

        val onGetInvokeField = Unobfuscator.loadGetInvokeField(classLoader)
        logDebug(Unobfuscator.getFieldDescriptor(onGetInvokeField))
        XposedBridge.hookMethod(onUpdateStatusChanged, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val obj = onGetInvokeField.get(param.args[0]) ?: return
                val method = ReflectionUtils.findMethodUsingFilter(
                    obj.javaClass
                ) { m -> m.returnType == Any::class.java } ?: return
                val statusListUpdates = ReflectionUtils.callMethod(method, obj) ?: return
                val lists = ReflectionUtils.findAllFieldsUsingFilter(
                    statusListUpdates.javaClass
                ) { f -> f.type == List::class.java }
                if (lists.isEmpty()) return
                val listObjects = ArrayList<List<*>>()
                for (f in lists) {
                    val list = f.get(statusListUpdates) as? List<*> ?: continue
                    listObjects.add(list)
                }
                updateStatusLists(listObjects)
                val act = WppCore.getCurrentActivity()
                for (mStatusContainer in mListStatusContainer) {
                    if (act != null && mStatusContainer.adapter == null) {
                        try {
                            mStatusContainer.adapter = IGStatusAdapter(act, statusInfoClass)
                        } catch (_: Throwable) {}
                    }
                    mStatusContainer.updateList()
                }
            }
        })
    }

    override fun getPluginName(): String {
        return "IGStatus"
    }
}
