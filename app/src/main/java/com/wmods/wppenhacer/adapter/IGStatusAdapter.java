package com.wmods.wppenhacer.adapter;

import static com.wmods.wppenhacer.xposed.features.customization.IGStatus.itens;
import static com.wmods.wppenhacer.xposed.features.customization.IGStatus.viewedItens;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.views.dialog.TabDialogContent;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class IGStatusAdapter extends ArrayAdapter {


    private final Class<?> clazzImageStatus;
    private final Class<?> statusInfoClazz;
    private final Method setCountStatus;

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        var currentItens = itens;
        if (currentItens == null || position >= currentItens.size()) {
            return convertView != null ? convertView : new View(getContext());
        }
        Object item;
        try {
            item = currentItens.get(position);
        } catch (Throwable t) {
            return convertView != null ? convertView : new View(getContext());
        }
        IGStatusViewHolder holder;
        if (convertView == null) {
            holder = new IGStatusViewHolder();
            convertView = createLayoutStatus(holder);
            convertView.setTag(holder);
        } else {
            holder = (IGStatusViewHolder) convertView.getTag();
        }
        if (position == 0 || item == null || Objects.equals(item, "my_status")) {
            holder.setInfo("my_status");
            holder.addButton.setVisibility(View.VISIBLE);
        } else {
            if (item instanceof View v) {
                v.setClickable(false);
            }
            holder.setInfo(item);
            holder.addButton.setVisibility(View.GONE);
        }
        convertView.setOnClickListener(v -> {
            if (holder.myStatus) {
                var activity = WppCore.getCurrentActivity();
                if (activity == null) return;
                var dialog = WppCore.createBottomDialog(activity);
                var tabdialog = new TabDialogContent(activity);
                tabdialog.setTitle(activity.getString(R.string.select_status_type));
                tabdialog.addTab(UnobfuscatorCache.getInstance().getString("mystatus"), DesignUtils.getIconByName("ic_status", true), (view) -> {
                    try {
                        var clazz = Unobfuscator.getClassByName("MyStatusesActivity", getContext().getClassLoader());
                        var intent = new Intent(WppCore.getCurrentActivity(), clazz);
                        WppCore.getCurrentActivity().startActivity(intent);
                    } catch (Exception e) {
                        Utils.showToast(e.getMessage(), 1);
                    }
                    dialog.dismissDialog();
                });

                // Botão da camera
                var iconCamera = DesignUtils.getDrawable(R.drawable.camera);
                DesignUtils.coloredDrawable(iconCamera, DesignUtils.isNightMode() ? Color.WHITE : Color.BLACK);
                tabdialog.addTab(activity.getString(R.string.open_camera), iconCamera, (view) -> {
                    try {
                        Intent intent = new Intent();
                        var clazz = Unobfuscator.getClassByName("CameraActivity", getContext().getClassLoader());
                        intent.setClassName(activity.getPackageName(), clazz.getName());
                        intent.putExtra("jid", "status@broadcast");
                        intent.putExtra("camera_origin", 4);
                        intent.putExtra("is_coming_from_chat", false);
                        intent.putExtra("media_sharing_user_journey_origin", 32);
                        intent.putExtra("media_sharing_user_journey_start_target", 9);
                        intent.putExtra("media_sharing_user_journey_chat_type", 4);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Utils.showToast(e.getMessage(), 1);
                    }
                    dialog.dismissDialog();
                });
                // Botão de editar
                var iconEdit = DesignUtils.getDrawable(R.drawable.edit2);
                DesignUtils.coloredDrawable(iconEdit, DesignUtils.isNightMode() ? Color.WHITE : Color.BLACK);

                tabdialog.addTab(activity.getString(R.string.edit_text), iconEdit, (view) -> {
                    try {
                        Intent intent = new Intent();
                        Class clazz;
                        try {
                            clazz = Unobfuscator.getClassByName("TextStatusComposerActivity", activity.getClassLoader());
                        } catch (Exception ignored) {
                            clazz = Unobfuscator.getClassByName("ConsolidatedStatusComposerActivity", getContext().getClassLoader());
                            intent.putExtra("status_composer_mode", 2);
                        }
                        intent.setClassName(activity.getPackageName(), clazz.getName());
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Utils.showToast(e.getMessage(), 1);
                    }
                    dialog.dismissDialog();
                });
                dialog.setContentView(tabdialog);
                dialog.showDialog();
                return;
            }
            try {
                var clazz = Unobfuscator.getClassByName("StatusPlaybackActivity", getContext().getClassLoader());
                var intent = new Intent(WppCore.getCurrentActivity(), clazz);
                String jidStr = null;
                if (holder.userJid != null) {
                    jidStr = holder.userJid.getPhoneRawString();
                    if (TextUtils.isEmpty(jidStr)) {
                        jidStr = holder.userJid.getRawJidString();
                    }
                    if (TextUtils.isEmpty(jidStr) && holder.userJid.userJid != null) {
                        jidStr = String.valueOf(holder.userJid.userJid);
                    }
                }
                if (!TextUtils.isEmpty(jidStr)) {
                    intent.putExtra("jid", jidStr);
                    if (holder.item != null) {
                        try {
                            viewedItens.add(holder.item);
                        } catch (Throwable ignored) {}
                    }
                    WppCore.getCurrentActivity().startActivity(intent);
                }
            } catch (Exception e) {
                Utils.showToast(e.getMessage(), 1);
            }
        });

        return convertView;
    }

    public IGStatusAdapter(@NonNull Context context, @NonNull Class<?> statusInfoClazz) throws Exception {
        super(context, 0);
        this.clazzImageStatus = Unobfuscator.findFirstClassUsingName(this.getContext().getClassLoader(), StringMatchType.EndsWith, ".ContactStatusThumbnail");
        this.statusInfoClazz = statusInfoClazz;
        Method mSetCount = ReflectionUtils.findMethodUsingFilter(this.clazzImageStatus, m -> m.getParameterCount() == 3 && Arrays.equals(new Class[]{int.class, int.class, int.class}, m.getParameterTypes()));
        if (mSetCount == null) {
            mSetCount = ReflectionUtils.findMethodUsingFilter(this.clazzImageStatus, m -> m.getParameterCount() == 2 && Arrays.equals(new Class[]{int.class, int.class}, m.getParameterTypes()));
        }
        this.setCountStatus = mSetCount;
    }

    @Override
    public int getCount() {
        var currentItens = itens;
        return currentItens != null ? currentItens.size() : 0;
    }

    class IGStatusViewHolder {
        public ImageView igStatusContactPhoto;
        public RelativeLayout addButton;
        public TextView igStatusContactName;
        public boolean myStatus;
        public FMessageWpp.UserJid userJid;
        public Object item;

        public void setInfo(Object item) {
            this.item = item;
            if (item == null || Objects.equals(item, "my_status")) {
                myStatus = true;
                String myStatusStr = "Status saya";
                try {
                    myStatusStr = UnobfuscatorCache.getInstance().getString("mystatus");
                } catch (Throwable ignored) {}
                igStatusContactName.setText(!TextUtils.isEmpty(myStatusStr) ? myStatusStr : "Status saya");
                Drawable profile = null;
                try {
                    profile = WppCore.getMyPhoto();
                } catch (Throwable ignored) {}
                if (profile == null) {
                    profile = Utils.getApplication().getDrawable(R.drawable.user_foreground);
                }
                igStatusContactPhoto.setImageDrawable(profile);
                setCountStatus(0, 0);
                return;
            }

            myStatus = false;
            try {
                // 1. Resolve UserJid using multi-layer extraction
                this.userJid = resolveUserJid(item);

                // 2. Resolve Contact Name
                String contactName = null;
                if (this.userJid != null && !this.userJid.isNull()) {
                    try {
                        contactName = WppCore.getContactName(this.userJid);
                    } catch (Throwable ignored) {}

                    if (TextUtils.isEmpty(contactName) || "Whatsapp Contact".equals(contactName)) {
                        try {
                            var waContact = WaContactWpp.getWaContactFromJid(this.userJid);
                            if (waContact != null) {
                                contactName = waContact.getDisplayName();
                                if (TextUtils.isEmpty(contactName)) {
                                    contactName = waContact.getWaName();
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (TextUtils.isEmpty(contactName) || "Whatsapp Contact".equals(contactName)) {
                        try {
                            contactName = this.userJid.getPhoneNumber();
                        } catch (Throwable ignored) {}
                    }

                    if (TextUtils.isEmpty(contactName) || "Whatsapp Contact".equals(contactName)) {
                        try {
                            contactName = this.userJid.getPhoneRawString();
                        } catch (Throwable ignored) {}
                    }
                }

                if (!TextUtils.isEmpty(contactName)) {
                    igStatusContactName.setText(contactName);
                } else {
                    igStatusContactName.setText("Status");
                }

                // 3. Resolve Contact Profile Photo
                Drawable profile = null;
                if (this.userJid != null && !this.userJid.isNull()) {
                    try {
                        var waContact = WaContactWpp.getWaContactFromJid(this.userJid);
                        if (waContact != null) {
                            var stream = waContact.getProfilePhoto(false);
                            if (stream != null) {
                                var bmp = android.graphics.BitmapFactory.decodeStream(stream);
                                if (bmp != null) {
                                    profile = new BitmapDrawable(getContext().getResources(), bmp);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                if (profile == null) {
                    try {
                        profile = DesignUtils.getDrawableByName("avatar_contact");
                    } catch (Throwable ignored) {}
                }
                if (profile == null) {
                    profile = Utils.getApplication().getDrawable(R.drawable.user_foreground);
                }
                igStatusContactPhoto.setImageDrawable(profile);

                // 4. Resolve Status Counts (unseen vs total)
                int countUnseen = 1;
                int total = 1;
                try {
                    StatusCounts counts = resolveStatusCounts(item);
                    countUnseen = counts.unseen;
                    total = counts.total;
                } catch (Throwable t) {
                    XposedBridge.log("[WaEnhancer] IGStatusAdapter resolveStatusCounts error: " + t);
                }

                setCountStatus(countUnseen, total);

            } catch (Throwable t) {
                XposedBridge.log("[WaEnhancer] IGStatusAdapter setInfo error: " + t);
            }
        }

        private static class StatusCounts {
            final int unseen;
            final int total;
            StatusCounts(int unseen, int total) {
                this.unseen = unseen;
                this.total = total;
            }
        }

        private StatusCounts resolveStatusCounts(Object item) {
            if (item == null) return new StatusCounts(0, 0);

            boolean isKnownViewed = false;
            try {
                if (viewedItens != null && viewedItens.contains(item)) {
                    isKnownViewed = true;
                }
            } catch (Throwable ignored) {}

            Object statusInfo = findStatusInfo(item, 0);
            if (statusInfo != null) {
                StatusCounts parsed = extractCountsFromStatusInfo(statusInfo);
                if (parsed != null) {
                    int unseen = isKnownViewed ? 0 : parsed.unseen;
                    int total = Math.max(parsed.total, unseen);
                    total = Math.max(1, total);
                    return new StatusCounts(unseen, total);
                }
            }

            if (isKnownViewed) {
                return new StatusCounts(0, 1);
            }
            return new StatusCounts(1, 1);
        }

        private Object findStatusInfo(Object obj, int depth) {
            if (obj == null || depth > 2) return null;
            if (extractCountsFromStatusInfo(obj) != null) {
                return obj;
            }
            Class<?> clazz = obj.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Class<?> type = f.getType();
                        if (type.isPrimitive() || type.getName().startsWith("java.lang.") || type.getName().startsWith("android.")) {
                            continue;
                        }
                        Object sub = f.get(obj);
                        if (sub != null && extractCountsFromStatusInfo(sub) != null) {
                            return sub;
                        }
                    } catch (Throwable ignored) {}
                }
                for (Method m : clazz.getDeclaredMethods()) {
                    try {
                        if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive() &&
                            !m.getReturnType().getName().startsWith("java.lang.") &&
                            !m.getReturnType().getName().startsWith("android.")) {
                            m.setAccessible(true);
                            Object res = m.invoke(obj);
                            if (res != null && extractCountsFromStatusInfo(res) != null) {
                                return res;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
            return null;
        }

        private StatusCounts extractCountsFromStatusInfo(Object obj) {
            if (obj == null) return null;
            Class<?> clazz = obj.getClass();
            if (clazz.isPrimitive() || clazz.getName().startsWith("java.") || clazz.getName().startsWith("android.")) {
                return null;
            }

            // 1. Try methods returning int (e.g. A01()=total, A02()=unseen, A03()=read in LX/85S)
            java.util.List<Method> intMethods = new java.util.ArrayList<>();
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    intMethods.add(m);
                }
            }
            if (intMethods.size() >= 3) {
                intMethods.sort((a, b) -> a.getName().compareTo(b.getName()));
                try {
                    int m0 = (int) intMethods.get(0).invoke(obj);
                    int m1 = (int) intMethods.get(1).invoke(obj);
                    int m2 = (int) intMethods.get(2).invoke(obj);
                    if (m0 > 0 && m1 >= 0 && m2 >= 0 && m0 == m1 + m2) {
                        return new StatusCounts(m1, m0);
                    }
                } catch (Throwable ignored) {}
            }

            // 2. Try declared int fields (e.g. A00=total, A01=unseen, A02=read in LX/85S)
            java.util.List<Field> intFields = new java.util.ArrayList<>();
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    intFields.add(f);
                }
            }
            if (intFields.size() >= 3) {
                intFields.sort((a, b) -> a.getName().compareTo(b.getName()));
                try {
                    int f0 = intFields.get(0).getInt(obj);
                    int f1 = intFields.get(1).getInt(obj);
                    int f2 = intFields.get(2).getInt(obj);
                    if (f0 > 0 && f1 >= 0 && f2 >= 0 && f0 == f1 + f2) {
                        return new StatusCounts(f1, f0);
                    }
                    if (f1 > 0 && f0 >= 0 && f2 >= 0 && f1 == f0 + f2) {
                        return new StatusCounts(f0, f1);
                    }
                    if (f2 > 0 && f0 >= 0 && f1 >= 0 && f2 == f0 + f1) {
                        return new StatusCounts(f0, f2);
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Fallback for 2 int fields (unseen, total)
            if (intFields.size() == 2) {
                intFields.sort((a, b) -> a.getName().compareTo(b.getName()));
                try {
                    int a = intFields.get(0).getInt(obj);
                    int b = intFields.get(1).getInt(obj);
                    if (a >= 0 && b > 0 && a <= b) {
                        return new StatusCounts(a, b);
                    }
                } catch (Throwable ignored) {}
            }

            return null;
        }

        private FMessageWpp.UserJid resolveUserJid(Object item) {
            if (item == null) return null;
            try {
                FMessageWpp.UserJid uj = FMessageWpp.UserJid.extractFrom(item);
                if (uj != null && !uj.isNull()) return uj;
            } catch (Throwable ignored) {}

            try {
                Object statusInfo = XposedHelpers.getObjectField(item, "A01");
                if (statusInfo != null) {
                    FMessageWpp.UserJid uj = FMessageWpp.UserJid.extractFrom(statusInfo);
                    if (uj != null && !uj.isNull()) return uj;
                }
            } catch (Throwable ignored) {}

            for (Field f : item.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(item);
                    if (val != null && !f.getType().isPrimitive() && !f.getType().getName().startsWith("java.lang.")) {
                        FMessageWpp.UserJid uj = FMessageWpp.UserJid.extractFrom(val);
                        if (uj != null && !uj.isNull()) return uj;
                    }
                } catch (Throwable ignored) {}
            }

            try {
                ClassLoader cl = getContext().getClassLoader();
                Class<?> classJid = Unobfuscator.findFirstClassUsingName(cl, StringMatchType.EndsWith, "jid.Jid");
                if (classJid != null) {
                    Object jidObj = findJidInObject(item, classJid, 0);
                    if (jidObj != null) {
                        return new FMessageWpp.UserJid(jidObj);
                    }
                }
            } catch (Throwable ignored) {}

            return null;
        }

        private Object findJidInObject(Object obj, Class<?> classJid, int depth) {
            if (obj == null || depth > 2) return null;
            if (classJid.isInstance(obj)) return obj;
            for (Field f : obj.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    if (classJid.isAssignableFrom(f.getType())) {
                        Object v = f.get(obj);
                        if (v != null) return v;
                    }
                } catch (Throwable ignored) {}
            }
            for (Field f : obj.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (!t.isPrimitive() && !t.getName().startsWith("java.") && !t.getName().startsWith("android.")) {
                        Object sub = f.get(obj);
                        if (sub != null) {
                            Object r = findJidInObject(sub, classJid, depth + 1);
                            if (r != null) return r;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            return null;
        }

        public void setCountStatus(int countUnseen, int total) {
            if (igStatusContactPhoto == null) return;
            if (setCountStatus != null) {
                try {
                    if (setCountStatus.getParameterCount() == 3) {
                        setCountStatus.invoke(igStatusContactPhoto, total, countUnseen, total);
                    } else if (setCountStatus.getParameterCount() == 2) {
                        setCountStatus.invoke(igStatusContactPhoto, countUnseen, total);
                    }
                    return;
                } catch (Throwable ignored) {}
            }
            try {
                for (Method m : igStatusContactPhoto.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 3 &&
                        m.getParameterTypes()[0] == int.class &&
                        m.getParameterTypes()[1] == int.class &&
                        m.getParameterTypes()[2] == int.class) {
                        m.setAccessible(true);
                        m.invoke(igStatusContactPhoto, total, countUnseen, total);
                        return;
                    }
                }
                for (Method m : igStatusContactPhoto.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 2 &&
                        m.getParameterTypes()[0] == int.class &&
                        m.getParameterTypes()[1] == int.class) {
                        m.setAccessible(true);
                        m.invoke(igStatusContactPhoto, countUnseen, total);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    @NonNull
    private RelativeLayout createLayoutStatus(IGStatusViewHolder holder) {
        RelativeLayout relativeLayout = new RelativeLayout(this.getContext());
        RelativeLayout.LayoutParams relativeParams = new RelativeLayout.LayoutParams(Utils.dipToPixels(86), ViewGroup.LayoutParams.WRAP_CONTENT);
        relativeLayout.setLayoutParams(relativeParams);

        // Criando o FrameLayout
        FrameLayout frameLayout = new FrameLayout(this.getContext());
        frameLayout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Criando o LinearLayout
        LinearLayout linearLayout = new LinearLayout(this.getContext());
        LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setLayoutParams(linearParams);

        // Criando o RelativeLayout interno
        RelativeLayout internalRelativeLayout = new RelativeLayout(this.getContext());
        RelativeLayout.LayoutParams internalRelativeParams = new RelativeLayout.LayoutParams(Utils.dipToPixels(64), Utils.dipToPixels(64));
        internalRelativeLayout.setLayoutParams(internalRelativeParams);

        // Adicionando os elementos ao RelativeLayout interno
        ImageView contactPhoto;
        if (this.clazzImageStatus != null) {
            try {
                contactPhoto = (ImageView) XposedHelpers.newInstance(this.clazzImageStatus, this.getContext());
            } catch (Throwable t) {
                contactPhoto = new ImageView(this.getContext());
            }
        } else {
            contactPhoto = new ImageView(this.getContext());
        }

        RelativeLayout.LayoutParams photoParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contactPhoto.setLayoutParams(photoParams);
        contactPhoto.setPadding(Utils.dipToPixels(2.5F), Utils.dipToPixels(2.5F), Utils.dipToPixels(2.5F), Utils.dipToPixels(2.5F));
        contactPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        contactPhoto.setImageDrawable(DesignUtils.getDrawableByName("avatar_contact"));
        holder.igStatusContactPhoto = contactPhoto;
        contactPhoto.setClickable(true);
        try {
            XposedHelpers.callMethod(contactPhoto, "setBorderSize", (float) Utils.dipToPixels(2.5f));
            XposedHelpers.callMethod(contactPhoto, "setCornerRadius", (float) Utils.dipToPixels(80f));
            try {
                XposedHelpers.setIntField(contactPhoto, "A02", Color.GRAY);
            } catch (Throwable ignored) {
                try {
                    XposedHelpers.setObjectField(contactPhoto, "A02", Color.GRAY);
                } catch (Throwable ignored2) {}
            }
            try {
                XposedHelpers.setIntField(contactPhoto, "A03", DesignUtils.getUnSeenColor());
            } catch (Throwable ignored) {
                try {
                    XposedHelpers.setObjectField(contactPhoto, "A03", DesignUtils.getUnSeenColor());
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        RelativeLayout addBtnRelativeLayout = new RelativeLayout(this.getContext());
        addBtnRelativeLayout.setBackgroundColor(Color.TRANSPARENT);
        RelativeLayout.LayoutParams addBtnParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addBtnParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        addBtnParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        addBtnParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        addBtnRelativeLayout.setLayoutParams(addBtnParams);
        addBtnRelativeLayout.setVisibility(View.GONE);

        ImageView iconImageView = new ImageView(this.getContext());
        RelativeLayout.LayoutParams iconParams = new RelativeLayout.LayoutParams(Utils.dipToPixels(24), Utils.dipToPixels(24));
        iconImageView.setLayoutParams(iconParams);
        var icon = DesignUtils.getDrawableByName("my_status_add_button_new");
        var coloredIcon = DesignUtils.generatePrimaryColorDrawable(icon);
        iconImageView.setImageDrawable(coloredIcon != null ? coloredIcon : icon);
        iconImageView.setBackgroundColor(Color.TRANSPARENT);
        addBtnRelativeLayout.addView(iconImageView);
        holder.addButton = addBtnRelativeLayout;

        internalRelativeLayout.addView(contactPhoto);
        internalRelativeLayout.addView(addBtnRelativeLayout);

        TextView contactName = new TextView(this.getContext());
        contactName.setEllipsize(TextUtils.TruncateAt.END);
        contactName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contactName.setLayoutParams(nameParams);
        contactName.setText("");
        contactName.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        contactName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        contactName.setTypeface(Typeface.DEFAULT_BOLD);
        contactName.setMaxLines(1);
        holder.igStatusContactName = contactName;
        linearLayout.addView(internalRelativeLayout);
        linearLayout.addView(contactName);
        frameLayout.addView(linearLayout);
        relativeLayout.addView(frameLayout);
        return relativeLayout;
    }
}
