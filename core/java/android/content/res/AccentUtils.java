package android.content.res;

import android.graphics.Color;
import android.os.SystemProperties;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;

/** @hide */
public class AccentUtils {
    private static final String TAG = "AccentUtils";

    private static ArrayList<String> accentResources = new ArrayList<>(
            Arrays.asList("accent_device_default_light",
                          "accent_material_dark",
                          "accent_material_light",
                          "material_pixel_blue_dark",
                          "material_pixel_blue_bright",
                          "omni_color5",
                          "omni_color4",
                          "dialer_theme_color",
                          "dialer_theme_color_dark",
                          "dialer_theme_color_20pct",
                          "colorAccent",
                          "avatar_bg_red",
                          "folder_indicator_color",
                          "accent_color_red",
                          "alert_dialog_color_accent_light",
                          "alert_dialog_color_accent_dark",
                          "oneplus_accent_text_color",
                          "accent_device_default",
                          "dismiss_all_icon_color",
                          "accent_device_default_dark",
                          "settingsHeaderColor",
                          "settings_accent_color",
                          "oneplus_accent_color",
                          "user_icon_1",
                          "user_icon_2",
                          "user_icon_3",
                          "user_icon_4",
                          "user_icon_5",
                          "user_icon_6",
                          "user_icon_7",
                          "user_icon_8"));

    private static final String ACCENT_COLOR_PROP = "persist.sys.theme.accentcolor";

    static boolean isResourceAccent(String resName) {
        for (String ar : accentResources)
            if (resName.contains(ar))
                return true;
        return false;
    }

    public static int getNewAccentColor(int defaultColor) {
        return getAccentColor(defaultColor, ACCENT_COLOR_PROP);
    }

    private static int getAccentColor(int defaultColor, String property) {
        try {
            String colorValue = SystemProperties.get(property, "-1");
            return "-1".equals(colorValue)
                    ? defaultColor : colorValue.equals("ff725aff")
                    ? defaultColor : Color.parseColor("#" + colorValue);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set accent: " + e.getMessage() +
                    "\nSetting default: " + defaultColor);
            return defaultColor;
        }
    }
}
