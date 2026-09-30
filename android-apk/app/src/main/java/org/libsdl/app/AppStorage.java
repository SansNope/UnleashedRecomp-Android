package org.libsdl.app;

import android.content.Context;

import java.io.File;

/** Paths shared by the launcher, document provider and native Android storage policy. */
final class AppStorage {
    private AppStorage() {}

    /**
     * App-specific dir under Android/media. Unlike Android/data, on-device file
     * managers can browse it on Android 11+, so it serves as a PC-less fallback
     * for game files and driver imports. Null when external storage is unavailable.
     */
    static File mediaBase(Context context) {
        File[] dirs = context.getExternalMediaDirs();
        return (dirs != null && dirs.length > 0) ? dirs[0] : null;
    }

    /** Mirrors the native GetDataRoot(): internal → Android/data → Android/media, populated wins. */
    static File activeGameRoot(Context context) {
        File internal = new File(context.getFilesDir(), "UnleashedRecomp");
        if (new File(internal, "game").isDirectory()) {
            return internal;
        }

        File externalBase = context.getExternalFilesDir(null);
        File external = externalBase != null ? new File(externalBase, "UnleashedRecomp") : null;
        if (external != null && new File(external, "game").isDirectory()) {
            return external;
        }

        File media = mediaBase(context);
        if (media != null) {
            File mediaRoot = new File(media, "UnleashedRecomp");
            if (new File(mediaRoot, "game").isDirectory()) {
                return mediaRoot;
            }
        }

        return external != null ? external : internal;
    }

    /**
     * Mirrors the native GetConfigRoot(): an existing install keeps ".config" next to
     * the game files it already has, so upgrading never relocates saves. A fresh
     * install prefers Android/media, since unlike Android/data it isn't hidden from
     * other apps by scoped storage on Android 11+ - so a raw-path tool such as
     * Syncthing can be pointed at the save folder directly, without SAF or root.
     */
    static File configRoot(Context context) {
        File dataRoot = activeGameRoot(context);
        if (new File(dataRoot, ".config/UnleashedRecomp").isDirectory()) {
            return dataRoot;
        }

        File media = mediaBase(context);
        if (media != null) {
            File mediaRoot = new File(media, "UnleashedRecomp");
            if (mediaRoot.isDirectory() || mediaRoot.mkdirs()) {
                return mediaRoot;
            }
        }

        return dataRoot;
    }

    static File configFile(Context context) {
        return new File(configRoot(context), ".config/UnleashedRecomp/config.toml");
    }

    /** Where native paths.cpp keeps save data: <config root>/.config/UnleashedRecomp/save. */
    static File saveDir(Context context) {
        return new File(configRoot(context), ".config/UnleashedRecomp/save");
    }

    static File transferRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    /** Primary import folder; SAF imports and marker files go here. */
    static File driverImportDir(Context context) {
        return new File(transferRoot(context), "driver_import");
    }

    /** All folders the native side scans for dropped drivers, primary first. */
    static File[] driverImportDirs(Context context) {
        File media = mediaBase(context);
        return media != null
            ? new File[] { driverImportDir(context), new File(media, "driver_import") }
            : new File[] { driverImportDir(context) };
    }
}
