package com.atakmap.android.hbc.plugin;

import android.content.Context;
import java.io.File;

/**
 * PluginNativeLoader
 *
 * Copied verbatim from the official ATAK plugin template:
 *   \\Primary\David\TAK Files\Development Files\atakplugintemplate-master
 *
 * Per ATAK security guidance: use absolute paths with System.load() rather than
 * System.loadLibrary(), whose behavior depends on environmental features that
 * can be manipulated.
 */
public class PluginNativeLoader {

    private static final String TAG = "PluginNativeLoader";
    private static String ndl = null;

    synchronized public static void init(final Context context) {
        if (ndl == null) {
            try {
                ndl = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0)
                    .nativeLibraryDir;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "native library loading will fail — unable to get nativeLibraryDir");
            }
        }
    }

    public static void loadLibrary(final String name) {
        if (ndl == null)
            throw new IllegalArgumentException("PluginNativeLoader not initialized");
        final String lib = ndl + File.separator + System.mapLibraryName(name);
        if (new File(lib).exists())
            System.load(lib);
    }
}
