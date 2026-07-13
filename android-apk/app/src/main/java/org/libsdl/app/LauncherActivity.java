package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lightweight pre-flight screen. It deliberately does not load SDL or Vulkan. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_DRIVER = 1001;
    private static final int REQUEST_GAME_ZIP = 1002;
    private static final int REQUEST_GAME_TREE = 1003;
    private static final int REQUEST_MOD_ZIP = 1004;
    private static final int REQUEST_MOD_TREE = 1005;
    private static final String[] DLC_DIRECTORIES = {
        "Apotos & Shamar Adventure Pack", "Chun-nan Adventure Pack",
        "Empire City & Adabat Adventure Pack", "Holoska Adventure Pack",
        "Mazuri Adventure Pack", "Spagonia Adventure Pack"
    };

    private TextView installStatus;
    private TextView driverStatus;
    private TextView diagnosticsStatus;
    private Button playButton;
    private Spinner driverSpinner;
    private Spinner renderSpinner;
    private CheckBox skipIntro;
    private CheckBox validation;
    private CheckBox gfxCapture;
    private CheckBox forceBc;
    private SharedPreferences prefs;
    private InstallState lastInstallState;

    private static final class InstallState {
        final boolean ready;
        final String message;
        InstallState(boolean ready, String message) {
            this.ready = ready;
            this.message = message;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getPreferences(MODE_PRIVATE);
        setContentView(buildPage());
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page);

        TextView title = text(getString(R.string.launcher_title), 28, true);
        page.addView(title);
        TextView subtitle = text(getString(R.string.launcher_subtitle), 15, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(3), 0, dp(14));
        page.addView(subtitle);

        LinearLayout files = card(R.string.launcher_game_files);
        installStatus = statusText();
        files.addView(installStatus);
        files.addView(button(R.string.launcher_install_game, view -> chooseInstallSource(true)));
        LinearLayout fileButtons = row();
        fileButtons.addView(button(R.string.launcher_open_files, view -> openFiles("game")), weighted());
        fileButtons.addView(button(R.string.launcher_recheck, view -> refreshStatuses()), weighted());
        files.addView(fileButtons);
        files.addView(button(R.string.launcher_open_saves, view -> openFiles("save")));
        page.addView(files);

        LinearLayout graphics = collapsibleCard(page, R.string.launcher_graphics, "expand_graphics", false);
        driverStatus = statusText();
        graphics.addView(driverStatus);
        driverSpinner = settingSpinner(graphics, R.string.launcher_driver,
            R.array.driver_labels);
        renderSpinner = settingSpinner(graphics, R.string.launcher_render_mode,
            R.array.render_mode_labels);
        LinearLayout driverButtons = row();
        driverButtons.addView(button(R.string.launcher_import_driver, view -> chooseDriver()), weighted());
        driverButtons.addView(button(R.string.launcher_driver_folder, view -> openFiles("transfer")), weighted());
        graphics.addView(driverButtons);

        // The runtime touch settings (on-screen controls, camera and stick) now live
        // in the in-game options menu; only the layout editor stays here as it launches
        // the game renderer.
        LinearLayout controls = card(R.string.launcher_controls);
        controls.addView(text(getString(R.string.launcher_controls_moved), 14, false));
        Button editLayout = button(R.string.launcher_edit_layout, view -> launchLayoutEditor());
        controls.addView(editLayout);
        page.addView(controls);

        LinearLayout mods = collapsibleCard(page, R.string.launcher_mods, "expand_mods", false);
        mods.addView(text(getString(R.string.launcher_mods_summary), 14, false));
        LinearLayout modButtons = row();
        modButtons.addView(button(R.string.launcher_manage_mods,
            view -> startActivity(new Intent(this, ModManagerActivity.class))), weighted());
        modButtons.addView(button(R.string.launcher_install_mod, view -> chooseInstallSource(false)), weighted());
        mods.addView(modButtons);

        LinearLayout debug = collapsibleCard(page, R.string.launcher_debug, "expand_debug", false);
        skipIntro = checkBox(R.string.launcher_skip_intro);
        validation = checkBox(R.string.launcher_validation);
        gfxCapture = checkBox(R.string.launcher_gfx_capture);
        forceBc = checkBox(R.string.launcher_force_bc);
        debug.addView(skipIntro);
        debug.addView(validation);
        debug.addView(gfxCapture);
        debug.addView(forceBc);
        diagnosticsStatus = statusText();
        debug.addView(diagnosticsStatus);
        debug.addView(button(R.string.launcher_open_logs, view -> openFiles("transfer")));

        playButton = button(R.string.launcher_play, view -> launchGame(false));
        playButton.setTextSize(18);
        LinearLayout.LayoutParams playParams = matchWrap();
        playParams.topMargin = dp(8);
        page.addView(playButton, playParams);
        return scroll;
    }

    private void refreshStatuses() {
        lastInstallState = inspectInstallation();
        installStatus.setText(lastInstallState.message);
        installStatus.setTextColor(lastInstallState.ready ? Color.rgb(25, 120, 55) : Color.rgb(180, 45, 35));
        playButton.setEnabled(lastInstallState.ready);

        File installedMarker = new File(getFilesDir(), "turnip/last_imported_driver.txt");
        File recoveryMarker = new File(getFilesDir(), "turnip/vulkan_startup_state.txt");
        int pending = 0;
        for (File importDir : AppStorage.driverImportDirs(this)) {
            pending += countDriverPackages(importDir);
        }
        String imported = readFirstLine(installedMarker);
        if (recoveryMarker.isFile()) {
            driverStatus.setText(R.string.launcher_driver_recovery);
            driverStatus.setTextColor(Color.rgb(180, 45, 35));
        } else if (pending > 0) {
            driverStatus.setText(getString(R.string.launcher_driver_pending, pending));
            driverStatus.setTextColor(Color.DKGRAY);
        } else if (!imported.isEmpty()) {
            driverStatus.setText(getString(R.string.launcher_driver_installed, imported));
            driverStatus.setTextColor(Color.DKGRAY);
        } else {
            driverStatus.setText(R.string.launcher_driver_builtin);
            driverStatus.setTextColor(Color.DKGRAY);
        }

        File log = new File(AppStorage.transferRoot(this), "log.txt");
        diagnosticsStatus.setText(log.isFile()
            ? getString(R.string.launcher_log_found, formatBytes(log.length()))
            : getString(R.string.launcher_log_missing));
    }

    private InstallState inspectInstallation() {
        File root = AppStorage.activeGameRoot(this);
        if (!root.exists() && !root.mkdirs()) {
            return new InstallState(false, getString(R.string.error_storage_create, root));
        }
        File probe = new File(root, ".launcher_write_probe");
        try {
            if (!probe.createNewFile() && !probe.isFile()) {
                return new InstallState(false, getString(R.string.error_storage_write, root));
            }
            probe.delete();
        } catch (IOException exception) {
            return new InstallState(false, getString(R.string.error_storage_write, root));
        }

        // patched/default.xex is intentionally not required here: since the raw-dump
        // installer landed, the native side creates it from game+update on first boot.
        LinkedHashMap<String, File> required = new LinkedHashMap<>();
        required.put("game/default.xex", new File(root, "game/default.xex"));
        required.put("update/default.xexp", new File(root, "update/default.xexp"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, File> item : required.entrySet()) {
            if (!item.getValue().isFile() || item.getValue().length() == 0) {
                missing.add(item.getKey());
            }
        }
        if (!missing.isEmpty()) {
            File misplaced = findFile(root, "default.xex", 3);
            if (misplaced != null && !misplaced.equals(required.get("game/default.xex")) &&
                !misplaced.equals(new File(root, "patched/default.xex"))) {
                return new InstallState(false, getString(R.string.error_game_nested,
                    relativePath(root, misplaced), root));
            }
            File[] rootFiles = root.listFiles();
            if (rootFiles == null || rootFiles.length == 0) {
                return new InstallState(false, getString(R.string.error_game_missing, root));
            }
            return new InstallState(false, getString(R.string.error_game_partial,
                joinLines(missing), root));
        }

        int dlc = 0;
        for (String directory : DLC_DIRECTORIES) {
            if (new File(root, "dlc/" + directory + "/DLC.xml").isFile()) dlc++;
        }
        return new InstallState(true, dlc == DLC_DIRECTORIES.length
            ? getString(R.string.game_ready_all_dlc)
            : getString(R.string.game_ready_missing_dlc, dlc, DLC_DIRECTORIES.length));
    }

    private void loadSettings() {
        Map<String, String> config = readConfig(AppStorage.configFile(this));
        select(driverSpinner, config.get("Video.VulkanDriver"),
            new String[] {"Auto", "System", "Bundled", "Vauzi710", "Imported"});
        select(renderSpinner, config.get("Video.RenderMode"),
            new String[] {"Auto", "GMEM", "Sysmem"});
        skipIntro.setChecked(Boolean.parseBoolean(config.get("Codes.SkipIntroLogos")));
        validation.setChecked(new File(getFilesDir(), "turnip/vk_layer_settings.txt").isFile());
        gfxCapture.setChecked(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt").isFile());
        forceBc.setChecked(new File(getFilesDir(), "force_bc.txt").isFile());
    }

    private boolean saveSettings() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("Video.VulkanDriver", quote(new String[] {"Auto", "System", "Bundled", "Vauzi710", "Imported"}[driverSpinner.getSelectedItemPosition()]));
        values.put("Video.RenderMode", quote(new String[] {"Auto", "GMEM", "Sysmem"}[renderSpinner.getSelectedItemPosition()]));
        values.put("Codes.SkipIntroLogos", Boolean.toString(skipIntro.isChecked()));
        try {
            patchConfig(AppStorage.configFile(this), values);
            setMarker(new File(getFilesDir(), "turnip/vk_layer_settings.txt"), validation.isChecked(),
                "khronos_validation.enables = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT\n");
            setMarker(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt"), gfxCapture.isChecked(), "");
            setMarker(new File(getFilesDir(), "force_bc.txt"), forceBc.isChecked(), "");
            return true;
        } catch (IOException exception) {
            showError(getString(R.string.error_settings_save, exception.getMessage()));
            return false;
        }
    }

    private void launchGame(boolean editControls) {
        if (!saveSettings()) return;
        InstallState current = inspectInstallation();
        if (!current.ready) {
            showError(current.message);
            refreshStatuses();
            return;
        }
        if (editControls) {
            try {
                setMarker(new File(AppStorage.activeGameRoot(this), "touch_layout_edit.txt"), true, "1\n");
            } catch (IOException exception) {
                showError(getString(R.string.error_layout_editor, exception.getMessage()));
                return;
            }
        }
        Intent game = new Intent(this, SDLActivity.class);
        game.putExtra("org.libsdl.app.EDIT_TOUCH_LAYOUT", editControls);
        startActivity(game);
    }

    private void launchLayoutEditor() {
        InstallState current = inspectInstallation();
        if (!current.ready) {
            showError(getString(R.string.error_layout_requires_game) + "\n\n" + current.message);
            return;
        }
        launchGame(true);
    }

    private void chooseDriver() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/octet-stream", "application/zip", "application/x-zip-compressed"
        });
        startActivityForResult(intent, REQUEST_DRIVER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        switch (requestCode) {
            case REQUEST_GAME_ZIP:
                startInstall(data.getData(), true, true);
                return;
            case REQUEST_GAME_TREE:
                startInstall(data.getData(), false, true);
                return;
            case REQUEST_MOD_ZIP:
                startInstall(data.getData(), true, false);
                return;
            case REQUEST_MOD_TREE:
                startInstall(data.getData(), false, false);
                return;
            case REQUEST_DRIVER:
                break;
            default:
                return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".so") && !lower.endsWith(".zip")) {
            showError(getString(R.string.error_driver_extension));
            return;
        }
        File directory = AppStorage.driverImportDir(this);
        directory.mkdirs();
        File target = uniqueFile(directory, safeName(name));
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Cannot open selected document");
            byte[] buffer = new byte[64 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new IOException("Selected file is empty");
            driverSpinner.setSelection(4);
            Toast.makeText(this, getString(R.string.driver_imported, target.getName()), Toast.LENGTH_LONG).show();
            refreshStatuses();
        } catch (IOException exception) {
            target.delete();
            showError(getString(R.string.error_driver_copy, exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Game files / mod installer: copy from a user-picked ZIP archive or
    // folder into the game root, locating the content root automatically.
    // ------------------------------------------------------------------

    private void chooseInstallSource(boolean gameFiles) {
        String[] items = { getString(R.string.install_source_zip), getString(R.string.install_source_folder) };
        new AlertDialog.Builder(this)
            .setTitle(gameFiles ? R.string.launcher_install_game : R.string.launcher_install_mod)
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/zip", "application/x-zip-compressed", "application/octet-stream" });
                    startActivityForResult(intent, gameFiles ? REQUEST_GAME_ZIP : REQUEST_MOD_ZIP);
                } else {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                        gameFiles ? REQUEST_GAME_TREE : REQUEST_MOD_TREE);
                }
            })
            .show();
    }

    /** One copyable file inside the picked source, addressed by a relative path. */
    private static final class SourceEntry {
        final String path;     // normalized, '/'-separated, no leading slash
        final Uri document;    // tree sources only; null for zip entries
        SourceEntry(String path, Uri document) {
            this.path = path;
            this.document = document;
        }
    }

    private static final class InstallProgress {
        volatile boolean cancelled;
        TextView label;
        AlertDialog dialog;
        int files;
        long bytes;
    }

    private void startInstall(Uri source, boolean isZip, boolean gameFiles) {
        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.install_scanning), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.install_progress_title)
            .setView(progress.label)
            .setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true)
            .show();

        String fallbackModName = safeName(stripZipExtension(queryDisplayName(source)));
        new Thread(() -> {
            try {
                List<SourceEntry> entries = isZip ? listZipEntries(source) : listTreeEntries(source);
                Map<SourceEntry, File> plan = gameFiles
                    ? planGameInstall(entries)
                    : planModInstall(entries, fallbackModName);
                int modCount = gameFiles ? 0 : countPlannedMods(plan);

                if (plan.isEmpty()) {
                    finishInstall(progress, getString(gameFiles
                        ? R.string.error_install_no_game : R.string.error_install_no_mod), false);
                    return;
                }

                if (isZip) {
                    copyZipEntries(source, plan, progress);
                } else {
                    copyTreeEntries(plan, progress);
                }

                if (progress.cancelled) {
                    finishInstall(progress, getString(R.string.install_cancelled), false);
                } else {
                    finishInstall(progress, gameFiles
                        ? getString(R.string.install_done_game)
                        : getString(R.string.install_done_mod, modCount), true);
                }
            } catch (Exception exception) {
                String reason = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
                finishInstall(progress, getString(R.string.error_install_failed, reason), false);
            }
        }, "installer").start();
    }

    private void finishInstall(InstallProgress progress, String message, boolean success) {
        runOnUiThread(() -> {
            progress.dialog.dismiss();
            new AlertDialog.Builder(this)
                .setTitle(success ? R.string.install_progress_title : R.string.error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            refreshStatuses();
        });
    }

    private void publishProgress(InstallProgress progress) {
        String status = getString(R.string.install_progress_status, progress.files, formatBytes(progress.bytes));
        runOnUiThread(() -> progress.label.setText(status));
    }

    /** Normalizes a zip/tree relative path; returns null when it must be skipped. */
    private static String normalizeRelativePath(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.endsWith("/")) return null;
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return path;
    }

    private List<SourceEntry> listZipEntries(Uri source) throws IOException {
        List<SourceEntry> entries = new ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                openSourceStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = normalizeRelativePath(entry.getName());
                if (path != null) entries.add(new SourceEntry(path, null));
            }
        }
        return entries;
    }

    private List<SourceEntry> listTreeEntries(Uri treeUri) {
        List<SourceEntry> entries = new ArrayList<>();
        listTreeChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", entries, 0);
        return entries;
    }

    private void listTreeChildren(Uri treeUri, String documentId, String prefix,
            List<SourceEntry> out, int depth) {
        if (depth > 8) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (android.database.Cursor cursor = getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (name == null || childId == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    listTreeChildren(treeUri, childId, prefix + name + "/", out, depth + 1);
                } else {
                    String path = normalizeRelativePath(prefix + name);
                    if (path != null) {
                        out.add(new SourceEntry(path,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)));
                    }
                }
            }
        }
    }

    /** Everything under the folder that holds game/default.xex goes into the game root. */
    private Map<SourceEntry, File> planGameInstall(List<SourceEntry> entries) {
        String marker = "game/default.xex";
        String prefix = null;
        for (SourceEntry entry : entries) {
            if (entry.path.equals(marker) || entry.path.endsWith("/" + marker)) {
                String candidate = entry.path.substring(0, entry.path.length() - marker.length());
                if (prefix == null || candidate.length() < prefix.length()) prefix = candidate;
            }
        }
        Map<SourceEntry, File> plan = new LinkedHashMap<>();
        if (prefix == null) return plan;
        File root = AppStorage.activeGameRoot(this);
        for (SourceEntry entry : entries) {
            if (entry.path.startsWith(prefix)) {
                plan.put(entry, new File(root, entry.path.substring(prefix.length())));
            }
        }
        return plan;
    }

    /** Each folder holding a mod.ini becomes <game root>/mods/<folder name>. */
    private Map<SourceEntry, File> planModInstall(List<SourceEntry> entries, String fallbackName) {
        List<String> roots = new ArrayList<>();
        for (SourceEntry entry : entries) {
            if (entry.path.equals("mod.ini") || entry.path.endsWith("/mod.ini")) {
                roots.add(entry.path.substring(0, entry.path.length() - "mod.ini".length()));
            }
        }
        // Outermost roots only: a nested mod.ini belongs to its parent mod's content.
        List<String> outer = new ArrayList<>();
        for (String root : roots) {
            boolean nested = false;
            for (String other : roots) {
                if (!other.equals(root) && root.startsWith(other)) { nested = true; break; }
            }
            if (!nested) outer.add(root);
        }

        Map<SourceEntry, File> plan = new LinkedHashMap<>();
        File modsDir = new File(AppStorage.activeGameRoot(this), "mods");
        for (SourceEntry entry : entries) {
            for (String root : outer) {
                if (!entry.path.startsWith(root)) continue;
                String folderName = root.isEmpty()
                    ? fallbackName
                    : root.substring(root.lastIndexOf('/', root.length() - 2) + 1, root.length() - 1);
                plan.put(entry, new File(new File(modsDir, safeName(folderName)),
                    entry.path.substring(root.length())));
                break;
            }
        }
        return plan;
    }

    private static int countPlannedMods(Map<SourceEntry, File> plan) {
        java.util.HashSet<String> modDirs = new java.util.HashSet<>();
        for (File destination : plan.values()) {
            File parent = destination;
            while (parent.getParentFile() != null
                    && !"mods".equals(parent.getParentFile().getName())) {
                parent = parent.getParentFile();
            }
            modDirs.add(parent.getName());
        }
        return modDirs.size();
    }

    private InputStream openSourceStream(Uri uri) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Cannot open the selected source");
        return input;
    }

    private void copyZipEntries(Uri source, Map<SourceEntry, File> plan, InstallProgress progress)
            throws IOException {
        Map<String, File> byPath = new LinkedHashMap<>();
        for (Map.Entry<SourceEntry, File> item : plan.entrySet()) {
            byPath.put(item.getKey().path, item.getValue());
        }
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                openSourceStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && !progress.cancelled) {
                if (entry.isDirectory()) continue;
                String path = normalizeRelativePath(entry.getName());
                File destination = path != null ? byPath.get(path) : null;
                if (destination == null) continue;
                copyStreamToFile(zip, destination, progress);
            }
        }
    }

    private void copyTreeEntries(Map<SourceEntry, File> plan, InstallProgress progress)
            throws IOException {
        for (Map.Entry<SourceEntry, File> item : plan.entrySet()) {
            if (progress.cancelled) return;
            try (InputStream input = openSourceStream(item.getKey().document)) {
                copyStreamToFile(input, item.getValue(), progress);
            }
        }
    }

    private void copyStreamToFile(InputStream input, File destination, InstallProgress progress)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            long sinceUpdate = 0;
            while ((count = input.read(buffer)) >= 0) {
                if (progress.cancelled) return;
                output.write(buffer, 0, count);
                progress.bytes += count;
                sinceUpdate += count;
                if (sinceUpdate >= 16 * 1024 * 1024) {
                    sinceUpdate = 0;
                    publishProgress(progress);
                }
            }
        }
        progress.files++;
        if (progress.files % 25 == 0) publishProgress(progress);
    }

    private static String stripZipExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip")
            ? name.substring(0, name.length() - 4) : name;
    }

    private void openFiles(String rootId) {
        try {
            Uri directory = DocumentsContract.buildRootUri(getPackageName() + ".documents", rootId);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(directory, "vnd.android.document/root");
            intent.setClipData(ClipData.newRawUri("Unleashed Recomp files", directory));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(intent);
        } catch (Exception exception) {
            showError(getString(R.string.error_open_files));
        }
    }

    private LinearLayout card(int titleId) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        TextView title = text(getString(titleId), 19, true);
        title.setPadding(0, 0, 0, dp(7));
        card.addView(title);
        return card;
    }

    /**
     * A card whose body collapses/expands when its header is tapped. The expanded
     * state is remembered per card in the activity preferences. Adds itself to
     * {@code page} and returns the body layout for the caller to populate.
     */
    private LinearLayout collapsibleCard(LinearLayout page, int titleId, String stateKey, boolean defaultExpanded) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);

        final LinearLayout body = column();
        final boolean expanded = prefs.getBoolean(stateKey, defaultExpanded);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);

        final String label = getString(titleId);
        final TextView title = text(label, 19, true);
        title.setPadding(0, 0, 0, dp(7));
        title.setText((expanded ? "▾  " : "▸  ") + label);
        title.setOnClickListener(view -> {
            boolean nowExpanded = body.getVisibility() != View.VISIBLE;
            body.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            title.setText((nowExpanded ? "▾  " : "▸  ") + label);
            prefs.edit().putBoolean(stateKey, nowExpanded).apply();
        });

        card.addView(title);
        card.addView(body);
        page.addView(card);
        return body;
    }

    private Spinner settingSpinner(LinearLayout parent, int labelId, int arrayId) {
        TextView label = text(getString(labelId), 14, true);
        parent.addView(label);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayId,
            android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, matchWrap());
        return spinner;
    }

    private TextView statusText() {
        TextView status = text("", 14, false);
        status.setPadding(0, 0, 0, dp(7));
        return status;
    }

    private CheckBox checkBox(int stringId) {
        CheckBox box = new CheckBox(this);
        box.setText(stringId);
        return box;
    }

    private Button button(int stringId, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(stringId);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(25, 25, 25));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private static String quote(String value) { return "\"" + value + "\""; }

    private static void select(Spinner spinner, String value, String[] options) {
        if (value == null) return;
        value = value.replace("\"", "").trim();
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static Map<String, String> readConfig(File file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!file.isFile()) return result;
        String section = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                } else if (!trimmed.startsWith("#") && !trimmed.isEmpty()) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) result.put(section + "." + trimmed.substring(0, equals).trim(),
                        trimmed.substring(equals + 1).trim());
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static void patchConfig(File file, LinkedHashMap<String, String> changes) throws IOException {
        List<String> lines = file.isFile()
            ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
            : new ArrayList<>();
        Map<String, Boolean> written = new LinkedHashMap<>();
        for (String key : changes.keySet()) written.put(key, false);
        String section = "";
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length() - 1).trim();
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || trimmed.startsWith("#")) continue;
            String full = section + "." + trimmed.substring(0, equals).trim();
            if (changes.containsKey(full)) {
                lines.set(i, trimmed.substring(0, equals).trim() + " = " + changes.get(full));
                written.put(full, true);
            }
        }
        for (String full : changes.keySet()) {
            if (written.get(full)) continue;
            int dot = full.indexOf('.');
            String wantedSection = full.substring(0, dot);
            String name = full.substring(dot + 1);
            int insertAt = lines.size();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.equals("[" + wantedSection + "]")) {
                    found = true;
                    insertAt = i + 1;
                    while (insertAt < lines.size() && !lines.get(insertAt).trim().startsWith("[")) insertAt++;
                    break;
                }
            }
            if (!found) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
                lines.add("[" + wantedSection + "]");
                insertAt = lines.size();
            }
            lines.add(insertAt, name + " = " + changes.get(full));
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File temporary = new File(parent, file.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            for (String line : lines) writer.write(line + "\n");
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setMarker(File file, boolean enabled, String contents) throws IOException {
        if (!enabled) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(contents);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
            new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return "vulkan_driver.so";
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static File uniqueFile(File directory, String name) {
        File result = new File(directory, name);
        if (!result.exists()) return result;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            result = new File(directory, base + "_" + i + extension);
            if (!result.exists()) return result;
        }
    }

    private static int countDriverPackages(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() &&
            (file.getName().toLowerCase(Locale.ROOT).endsWith(".so") ||
             file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")));
        return files == null ? 0 : files.length;
    }

    private static String readFirstLine(File file) {
        if (!file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) { return ""; }
    }

    private static File findFile(File root, String name, int depth) {
        if (depth < 0) return null;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && child.getName().equalsIgnoreCase(name)) return child;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFile(child, name, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String relativePath(File root, File file) {
        try { return root.toPath().relativize(file.toPath()).toString(); }
        catch (Exception ignored) { return file.toString(); }
    }

    private static String joinLines(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append("\n• ").append(value);
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
