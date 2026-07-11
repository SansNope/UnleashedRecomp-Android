package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
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
    private Spinner touchSpinner;
    private CheckBox showFps;
    private CheckBox showProfiler;
    private CheckBox skipIntro;
    private CheckBox validation;
    private CheckBox gfxCapture;
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
        LinearLayout fileButtons = row();
        fileButtons.addView(button(R.string.launcher_open_files, view -> openFiles("game:")), weighted());
        fileButtons.addView(button(R.string.launcher_recheck, view -> refreshStatuses()), weighted());
        files.addView(fileButtons);
        page.addView(files);

        LinearLayout graphics = card(R.string.launcher_graphics);
        driverStatus = statusText();
        graphics.addView(driverStatus);
        driverSpinner = settingSpinner(graphics, R.string.launcher_driver,
            R.array.driver_labels);
        renderSpinner = settingSpinner(graphics, R.string.launcher_render_mode,
            R.array.render_mode_labels);
        LinearLayout driverButtons = row();
        driverButtons.addView(button(R.string.launcher_import_driver, view -> chooseDriver()), weighted());
        driverButtons.addView(button(R.string.launcher_driver_folder, view -> openFiles("transfer:")), weighted());
        graphics.addView(driverButtons);
        page.addView(graphics);

        LinearLayout controls = card(R.string.launcher_controls);
        touchSpinner = settingSpinner(controls, R.string.launcher_touch_policy,
            R.array.touch_policy_labels);
        Button editLayout = button(R.string.launcher_edit_layout, view -> launchLayoutEditor());
        controls.addView(editLayout);
        page.addView(controls);

        LinearLayout mods = card(R.string.launcher_mods);
        mods.addView(text(getString(R.string.launcher_mods_summary), 14, false));
        mods.addView(button(R.string.launcher_manage_mods,
            view -> startActivity(new Intent(this, ModManagerActivity.class))));
        page.addView(mods);

        LinearLayout debug = card(R.string.launcher_debug);
        showFps = checkBox(R.string.launcher_show_fps);
        showProfiler = checkBox(R.string.launcher_show_profiler);
        skipIntro = checkBox(R.string.launcher_skip_intro);
        validation = checkBox(R.string.launcher_validation);
        gfxCapture = checkBox(R.string.launcher_gfx_capture);
        debug.addView(showFps);
        debug.addView(showProfiler);
        debug.addView(skipIntro);
        debug.addView(validation);
        debug.addView(gfxCapture);
        diagnosticsStatus = statusText();
        debug.addView(diagnosticsStatus);
        debug.addView(button(R.string.launcher_open_logs, view -> openFiles("transfer:")));
        page.addView(debug);

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

        LinkedHashMap<String, File> required = new LinkedHashMap<>();
        required.put("game/default.xex", new File(root, "game/default.xex"));
        required.put("update/default.xexp", new File(root, "update/default.xexp"));
        required.put("patched/default.xex", new File(root, "patched/default.xex"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, File> item : required.entrySet()) {
            if (!item.getValue().isFile() || item.getValue().length() == 0) {
                missing.add(item.getKey());
            }
        }
        if (!missing.isEmpty()) {
            File misplaced = findFile(root, "default.xex", 3);
            if (misplaced != null && !misplaced.equals(required.get("game/default.xex")) &&
                !misplaced.equals(required.get("patched/default.xex"))) {
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
        select(touchSpinner, config.get("Input.TouchControls"),
            new String[] {"Auto", "Always On", "Off"});
        showFps.setChecked(Boolean.parseBoolean(config.get("Video.ShowFPS")));
        showProfiler.setChecked(Boolean.parseBoolean(config.get("Video.ShowProfiler")));
        skipIntro.setChecked(Boolean.parseBoolean(config.get("Codes.SkipIntroLogos")));
        validation.setChecked(new File(getFilesDir(), "turnip/vk_layer_settings.txt").isFile());
        gfxCapture.setChecked(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt").isFile());
    }

    private boolean saveSettings() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("Video.VulkanDriver", quote(new String[] {"Auto", "System", "Bundled", "Vauzi710", "Imported"}[driverSpinner.getSelectedItemPosition()]));
        values.put("Video.RenderMode", quote(new String[] {"Auto", "GMEM", "Sysmem"}[renderSpinner.getSelectedItemPosition()]));
        values.put("Video.ShowFPS", Boolean.toString(showFps.isChecked()));
        values.put("Video.ShowProfiler", Boolean.toString(showProfiler.isChecked()));
        values.put("Input.TouchControls", quote(new String[] {"Auto", "Always On", "Off"}[touchSpinner.getSelectedItemPosition()]));
        values.put("Codes.SkipIntroLogos", Boolean.toString(skipIntro.isChecked()));
        try {
            patchConfig(AppStorage.configFile(this), values);
            setMarker(new File(getFilesDir(), "turnip/vk_layer_settings.txt"), validation.isChecked(),
                "khronos_validation.enables = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT\n");
            setMarker(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt"), gfxCapture.isChecked(), "");
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
        if (requestCode != REQUEST_DRIVER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
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

    private void openFiles(String documentId) {
        try {
            Uri directory = DocumentsContract.buildRootUri(getPackageName() + ".documents",
                documentId.startsWith("game") ? "game" : "transfer");
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
