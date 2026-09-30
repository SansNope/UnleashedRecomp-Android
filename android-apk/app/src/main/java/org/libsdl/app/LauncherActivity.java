package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private static final int REQUEST_GAME_PACKAGES = 1006;
    private static final int REQUEST_CUSTOM_BACKGROUND = 1007;
    private static final int REQUEST_CUSTOM_LOGO = 1008;
    private static final int DRIVER_EXPERIMENTAL_A725 = 4;
    private static final int DRIVER_IMPORTED = 5;
    private static final int RENDER_MODE_SYSMEM = 2;
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String[] DRIVER_VALUES = {
        "Auto", "System", "Bundled", "Vauzi710", "ExperimentalA725", "Imported"
    };
    private static final String[] RENDER_MODE_VALUES = {"Auto", "GMEM", "Sysmem"};
    private static final String[] DLC_DIRECTORIES = {
        "Apotos & Shamar Adventure Pack", "Chun-nan Adventure Pack",
        "Empire City & Adabat Adventure Pack", "Holoska Adventure Pack",
        "Mazuri Adventure Pack", "Spagonia Adventure Pack"
    };

    // Settings-page views; null while the home page is showing.
    private TextView installStatus;
    private TextView driverStatus;
    private TextView diagnosticsStatus;
    private TextView updateStatus;
    private Button updateButton;
    private Spinner driverSpinner;
    private Spinner renderSpinner;
    private CheckBox skipIntro;
    private CheckBox validation;
    private CheckBox gfxCapture;
    private CheckBox forceBc;

    // Home-page views; null while the settings page is showing.
    private Button playButton;
    private TextView homeHint;

    private SharedPreferences prefs;
    private InstallState lastInstallState;
    private boolean onSettingsPage;
    private int settingsSection;

    private static final String STATE_ON_SETTINGS_PAGE = "on_settings_page";
    private static final String STATE_SETTINGS_SECTION = "settings_section";

    private static final int SECTION_FILES = 0;
    private static final int SECTION_APPEARANCE = 1;
    private static final int SECTION_UPDATES = 2;
    private static final int SECTION_GRAPHICS = 3;
    private static final int SECTION_CONTROLS = 4;
    private static final int SECTION_MODS = 5;
    private static final int SECTION_DEBUG = 6;

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
        // See SDLActivity.onCreate(): the platform default keeps window content out of
        // the display cutout in landscape unless this is set explicitly.
        if (Build.VERSION.SDK_INT >= 28 /* Android 9 (P) */) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }
        hideSystemBars();
        prefs = getPreferences(MODE_PRIVATE);
        onSettingsPage = state != null && state.getBoolean(STATE_ON_SETTINGS_PAGE, false);
        settingsSection = state != null ? state.getInt(STATE_SETTINGS_SECTION, SECTION_FILES) : SECTION_FILES;
        setContentView(onSettingsPage ? buildSettingsPage() : buildHomePage());
        if (onSettingsPage) loadSettings();
        maybeCheckForUpdates();
    }

    /** Hides the status and navigation bars, matching SDLActivity's immersive-sticky
     *  setup - without this the launcher shows a system status bar tray the game
     *  itself never does. */
    private void hideSystemBars() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ON_SETTINGS_PAGE, onSettingsPage);
        outState.putInt(STATE_SETTINGS_SECTION, settingsSection);
    }

    @Override
    public void onBackPressed() {
        if (onSettingsPage) {
            openHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateManager.resumePendingInstall(this);
        refreshStatuses();
    }

    private void openSettings() {
        onSettingsPage = true;
        settingsSection = SECTION_FILES;
        setContentView(buildSettingsPage());
        loadSettings();
        refreshStatuses();
    }

    /** Saves whatever the current section holds, then rebuilds on the new one. */
    private void switchSection(int section) {
        saveSettings();
        settingsSection = section;
        setContentView(buildSettingsPage());
        loadSettings();
        refreshStatuses();
    }

    private void openHome() {
        saveSettings();
        onSettingsPage = false;
        setContentView(buildHomePage());
        refreshStatuses();
    }

    /**
     * Home page: the game's own branding, a hint when it isn't ready to play, the
     * Play button, and a link into the settings page. Everything technical
     * (install management, driver/mod/debug configuration) lives in buildSettingsPage()
     * instead, so this first screen a home-screen launcher shortcut lands on reads as
     * part of the game rather than an app settings/debug panel.
     */
    private View buildHomePage() {
        FrameLayout root = new FrameLayout(this);

        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (customBackgroundFile().isFile()) {
            background.setImageURI(Uri.fromFile(customBackgroundFile()));
        } else {
            background.setImageResource(R.drawable.bg_launcher_gradient);
        }
        root.addView(background, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout column = column();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
        root.addView(column, columnParams);

        ImageView logo = new ImageView(this);
        if (customLogoFile().isFile()) {
            logo.setImageURI(Uri.fromFile(customLogoFile()));
        } else {
            logo.setImageResource(R.drawable.img_logo);
        }
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
            dp(380), LinearLayout.LayoutParams.WRAP_CONTENT);
        column.addView(logo, logoParams);

        homeHint = text("", 14, false);
        homeHint.setTextColor(Color.rgb(225, 170, 90));
        homeHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(16);
        homeHint.setLayoutParams(hintParams);
        column.addView(homeHint);

        playButton = new Button(this);
        playButton.setText(R.string.launcher_play);
        playButton.setAllCaps(false);
        playButton.setTextSize(22);
        playButton.setLetterSpacing(0.03f);
        playButton.setTextColor(Color.rgb(30, 20, 5));
        // Oswald Bold (OFL-licensed, google/fonts): a condensed display sans in the same
        // spirit as the title's own "Tw Cen MT Condensed Extra Bold"/"Syntax Ultra Black"
        // treatment, with the full Cyrillic coverage those two commercial fonts don't
        // have and which bundling here has no redistribution rights for.
        playButton.setTypeface(getResources().getFont(R.font.oswald_bold));
        playButton.setBackgroundResource(R.drawable.bg_play_button);
        playButton.setOnClickListener(view -> launchGame(false));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(280), dp(60));
        playParams.topMargin = dp(20);
        column.addView(playButton, playParams);

        LinearLayout links = row();
        links.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams linksParams = matchWrap();
        linksParams.topMargin = dp(18);
        column.addView(links, linksParams);

        TextView settingsLink = text(getString(R.string.launcher_settings), 15, false);
        settingsLink.setTextColor(Color.rgb(150, 150, 165));
        settingsLink.setGravity(Gravity.CENTER);
        settingsLink.setPadding(dp(18), 0, dp(18), dp(6));
        settingsLink.setOnClickListener(view -> openSettings());
        links.addView(settingsLink);

        TextView exitLink = text(getString(R.string.launcher_exit), 15, false);
        exitLink.setTextColor(Color.rgb(150, 150, 165));
        exitLink.setGravity(Gravity.CENTER);
        exitLink.setPadding(dp(18), 0, dp(18), dp(6));
        exitLink.setOnClickListener(view -> finishAffinity());
        links.addView(exitLink);

        return root;
    }

    private static final int[] SETTINGS_SECTION_TITLES = {
        R.string.launcher_game_files, R.string.launcher_appearance, R.string.launcher_updates,
        R.string.launcher_graphics, R.string.launcher_controls, R.string.launcher_mods,
        R.string.launcher_debug
    };

    private View buildSettingsPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 16, 24));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(page, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout sidebar = column();
        sidebar.setBackgroundColor(Color.rgb(20, 20, 30));
        // The activity draws edge-to-edge, so the first item would otherwise land
        // partly under the status bar; dp(14) alone isn't enough to clear it.
        sidebar.setPadding(dp(14), dp(30), dp(14), dp(14));
        page.addView(sidebar, new LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView back = text(getString(R.string.launcher_back_to_home), 15, false);
        back.setPadding(dp(10), 0, dp(10), dp(22));
        back.setOnClickListener(view -> openHome());
        sidebar.addView(back);

        for (int i = 0; i < SETTINGS_SECTION_TITLES.length; i++) {
            final int section = i;
            boolean selected = settingsSection == i;
            TextView item = text(getString(SETTINGS_SECTION_TITLES[i]), 16, selected);
            item.setPadding(dp(14), dp(14), dp(14), dp(14));
            item.setBackgroundResource(R.drawable.bg_sidebar_item);
            item.setSelected(selected);
            LinearLayout.LayoutParams itemParams = matchWrap();
            itemParams.bottomMargin = dp(4);
            item.setLayoutParams(itemParams);
            item.setOnClickListener(view -> {
                if (settingsSection != section) switchSection(section);
            });
            sidebar.addView(item);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(30), dp(24), dp(28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        switch (settingsSection) {
            case SECTION_APPEARANCE: buildAppearanceSection(content); break;
            case SECTION_UPDATES: buildUpdatesSection(content); break;
            case SECTION_GRAPHICS: buildGraphicsSection(content); break;
            case SECTION_CONTROLS: buildControlsSection(content); break;
            case SECTION_MODS: buildModsSection(content); break;
            case SECTION_DEBUG: buildDebugSection(content); break;
            case SECTION_FILES:
            default: buildFilesSection(content); break;
        }

        return root;
    }

    private void buildFilesSection(LinearLayout content) {
        LinearLayout files = panel();
        installStatus = statusText();
        files.addView(installStatus);
        files.addView(button(R.string.launcher_install_game, view -> chooseInstallSource(true)));
        LinearLayout fileButtons = row();
        fileButtons.addView(button(R.string.launcher_open_files, view -> openFiles("game")), weighted());
        fileButtons.addView(button(R.string.launcher_recheck, view -> refreshStatuses()), weighted());
        files.addView(fileButtons);
        files.addView(button(R.string.launcher_open_saves, view -> openFiles("save")));
        content.addView(files);
    }

    private void buildAppearanceSection(LinearLayout content) {
        LinearLayout appearance = panel();
        appearance.addView(text(getString(R.string.launcher_appearance_summary), 14, false));

        LinearLayout bgRow = row();
        bgRow.addView(button(R.string.launcher_choose_background,
            view -> chooseCustomImage(REQUEST_CUSTOM_BACKGROUND)), weighted());
        if (customBackgroundFile().isFile()) {
            bgRow.addView(button(R.string.launcher_reset_background, view -> {
                customBackgroundFile().delete();
                switchSection(SECTION_APPEARANCE);
            }), weighted());
        }
        appearance.addView(bgRow);

        LinearLayout logoRow = row();
        logoRow.addView(button(R.string.launcher_choose_logo,
            view -> chooseCustomImage(REQUEST_CUSTOM_LOGO)), weighted());
        if (customLogoFile().isFile()) {
            logoRow.addView(button(R.string.launcher_reset_logo, view -> {
                customLogoFile().delete();
                switchSection(SECTION_APPEARANCE);
            }), weighted());
        }
        appearance.addView(logoRow);

        content.addView(appearance);
    }

    private void buildUpdatesSection(LinearLayout content) {
        LinearLayout updates = panel();
        updateStatus = statusText();
        updateStatus.setText(getString(R.string.update_current_version, UpdateManager.currentVersion(this)));
        updates.addView(updateStatus);
        updateButton = button(R.string.update_check, view -> checkForUpdates(true));
        updates.addView(updateButton);
        content.addView(updates);
    }

    private void buildGraphicsSection(LinearLayout content) {
        LinearLayout graphics = panel();
        driverStatus = statusText();
        graphics.addView(driverStatus);
        driverSpinner = settingSpinner(graphics, R.string.launcher_driver, R.array.driver_labels);
        renderSpinner = settingSpinner(graphics, R.string.launcher_render_mode, R.array.render_mode_labels);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyDriverPresetToLauncher();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                applyDriverPresetToLauncher();
            }
        });
        LinearLayout driverButtons = row();
        driverButtons.addView(button(R.string.launcher_import_driver, view -> chooseDriver()), weighted());
        driverButtons.addView(button(R.string.launcher_driver_folder, view -> openFiles("transfer")), weighted());
        graphics.addView(driverButtons);
        content.addView(graphics);
    }

    private void buildControlsSection(LinearLayout content) {
        // The runtime touch settings (on-screen controls, camera and stick) now live
        // in the in-game options menu; only the layout editor stays here as it launches
        // the game renderer.
        LinearLayout controls = panel();
        controls.addView(text(getString(R.string.launcher_controls_moved), 14, false));
        controls.addView(button(R.string.launcher_edit_layout, view -> launchLayoutEditor()));
        content.addView(controls);
    }

    private void buildModsSection(LinearLayout content) {
        LinearLayout mods = panel();
        mods.addView(text(getString(R.string.launcher_mods_summary), 14, false));
        LinearLayout modButtons = row();
        modButtons.addView(button(R.string.launcher_manage_mods,
            view -> startActivity(new Intent(this, ModManagerActivity.class))), weighted());
        modButtons.addView(button(R.string.launcher_install_mod, view -> chooseInstallSource(false)), weighted());
        mods.addView(modButtons);
        content.addView(mods);
    }

    private void buildDebugSection(LinearLayout content) {
        LinearLayout debug = panel();
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
        content.addView(debug);
    }

    /** Updates whichever page's views currently exist - settings, home, or (transiently) neither. */
    private void refreshStatuses() {
        lastInstallState = inspectInstallation();
        boolean stagedInstall = hasStagedGamePackages();
        boolean ready = lastInstallState.ready || stagedInstall;

        if (installStatus != null) {
            installStatus.setText(stagedInstall && !lastInstallState.ready
                ? getString(R.string.launcher_install_staged)
                : lastInstallState.message);
            installStatus.setTextColor(lastInstallState.ready ? Color.rgb(100, 210, 130)
                : stagedInstall ? Color.rgb(230, 165, 65) : Color.rgb(235, 95, 85));
        }

        if (playButton != null) {
            playButton.setEnabled(ready);
        }
        if (homeHint != null) {
            homeHint.setText(lastInstallState.ready ? ""
                : stagedInstall ? getString(R.string.launcher_home_staged)
                : getString(R.string.launcher_home_not_ready));
        }

        if (driverStatus != null) {
            File installedMarker = new File(getFilesDir(), "turnip/last_imported_driver.txt");
            File recoveryMarker = new File(getFilesDir(), "turnip/vulkan_startup_state.txt");
            int pending = 0;
            for (File importDir : AppStorage.driverImportDirs(this)) {
                pending += countDriverPackages(importDir);
            }
            String imported = readFirstLine(installedMarker);
            if (recoveryMarker.isFile()) {
                driverStatus.setText(R.string.launcher_driver_recovery);
                driverStatus.setTextColor(Color.rgb(235, 95, 85));
            } else if (pending > 0) {
                driverStatus.setText(getString(R.string.launcher_driver_pending, pending));
                driverStatus.setTextColor(Color.rgb(170, 170, 182));
            } else if (!imported.isEmpty()) {
                driverStatus.setText(getString(R.string.launcher_driver_installed, imported));
                driverStatus.setTextColor(Color.rgb(170, 170, 182));
            } else {
                driverStatus.setText(R.string.launcher_driver_builtin);
                driverStatus.setTextColor(Color.rgb(170, 170, 182));
            }
        }

        if (diagnosticsStatus != null) {
            File log = new File(AppStorage.transferRoot(this), "log.txt");
            diagnosticsStatus.setText(log.isFile()
                ? getString(R.string.launcher_log_found, formatBytes(log.length()))
                : getString(R.string.launcher_log_missing));
        }
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

    /** Loads whichever section's widgets currently exist - each section builds only
     *  its own fields, so most of these are null except right after their section
     *  was just built. */
    private void loadSettings() {
        Map<String, String> config = readConfig(AppStorage.configFile(this));
        if (driverSpinner != null) {
            select(driverSpinner, config.get("Video.VulkanDriver"), DRIVER_VALUES);
            select(renderSpinner, config.get("Video.RenderMode"), RENDER_MODE_VALUES);
            applyDriverPresetToLauncher();
        }
        if (skipIntro != null) {
            skipIntro.setChecked(Boolean.parseBoolean(config.get("Codes.SkipIntroLogos")));
        }
        if (validation != null) {
            validation.setChecked(new File(getFilesDir(), "turnip/vk_layer_settings.txt").isFile());
        }
        if (gfxCapture != null) {
            gfxCapture.setChecked(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt").isFile());
        }
        if (forceBc != null) {
            forceBc.setChecked(new File(getFilesDir(), "force_bc.txt").isFile());
        }
    }

    /** Persists whichever section's widgets currently exist. Each of the settings
     *  sections that holds persisted state is independent, since only one is built
     *  (and can hold pending changes) at a time. */
    private boolean saveSettings() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (driverSpinner != null) {
            applyDriverPresetToLauncher();
            values.put("Video.VulkanDriver", quote(DRIVER_VALUES[driverSpinner.getSelectedItemPosition()]));
            values.put("Video.RenderMode", quote(RENDER_MODE_VALUES[renderSpinner.getSelectedItemPosition()]));
        }
        if (skipIntro != null) {
            values.put("Codes.SkipIntroLogos", Boolean.toString(skipIntro.isChecked()));
        }
        try {
            if (!values.isEmpty()) {
                patchConfig(AppStorage.configFile(this), values);
            }
            if (validation != null) {
                setMarker(new File(getFilesDir(), "turnip/vk_layer_settings.txt"), validation.isChecked(),
                    "khronos_validation.enables = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT\n");
            }
            if (gfxCapture != null) {
                setMarker(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt"), gfxCapture.isChecked(), "");
            }
            if (forceBc != null) {
                setMarker(new File(getFilesDir(), "force_bc.txt"), forceBc.isChecked(), "");
            }
            return true;
        } catch (IOException exception) {
            showError(getString(R.string.error_settings_save, exception.getMessage()));
            return false;
        }
    }

    private void launchGame(boolean editControls) {
        if (!saveSettings()) return;
        InstallState current = inspectInstallation();
        if (!current.ready && !hasStagedGamePackages()) {
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
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_GAME_PACKAGES) {
            stageGamePackages(data);
            return;
        }
        if (data.getData() == null) return;
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
            case REQUEST_CUSTOM_BACKGROUND:
                importCustomImage(data.getData(), customBackgroundFile(), 2200);
                return;
            case REQUEST_CUSTOM_LOGO:
                importCustomImage(data.getData(), customLogoFile(), 1200);
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
            driverSpinner.setSelection(DRIVER_IMPORTED);
            Toast.makeText(this, getString(R.string.driver_imported, target.getName()), Toast.LENGTH_LONG).show();
            refreshStatuses();
        } catch (IOException exception) {
            target.delete();
            showError(getString(R.string.error_driver_copy, exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Home screen appearance: an optional user-picked background/logo image,
    // stored as a private copy so the picker's grant doesn't need to outlive
    // this session. Purely cosmetic - unrelated to game saves/config.
    // ------------------------------------------------------------------

    private File customBackgroundFile() {
        return new File(getFilesDir(), "launcher_appearance/background.png");
    }

    private File customLogoFile() {
        return new File(getFilesDir(), "launcher_appearance/logo.png");
    }

    private void chooseCustomImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, requestCode);
    }

    /** Downscales and copies a picked gallery image to `destination` as PNG, then
     *  rebuilds whichever page is showing so the change is visible immediately. */
    private void importCustomImage(Uri source, File destination, int maxDimension) {
        try {
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream probe = openSourceStream(source)) {
                BitmapFactory.decodeStream(probe, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream input = openSourceStream(source)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) throw new IOException("Unsupported image format");

            try (FileOutputStream output = new FileOutputStream(destination)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();

            if (onSettingsPage) {
                setContentView(buildSettingsPage());
                loadSettings();
                refreshStatuses();
            }
        } catch (IOException exception) {
            showError(getString(R.string.error_image_import, exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Game files / mod installer: copy from a user-picked ZIP archive or
    // folder into the game root, locating the content root automatically.
    // ------------------------------------------------------------------

    private void chooseInstallSource(boolean gameFiles) {
        String[] items = gameFiles
            ? new String[] { getString(R.string.install_source_zip), getString(R.string.install_source_folder),
                getString(R.string.install_source_iso_packages) }
            : new String[] { getString(R.string.install_source_zip), getString(R.string.install_source_folder) };
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
                } else if (which == 1) {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                        gameFiles ? REQUEST_GAME_TREE : REQUEST_MOD_TREE);
                } else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, REQUEST_GAME_PACKAGES);
                }
            })
            .show();
    }

    private void maybeCheckForUpdates() {
        long lastCheck = prefs.getLong("update_last_check", 0);
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            checkForUpdates(false);
        }
    }

    private void checkForUpdates(boolean manual) {
        if (updateButton != null) updateButton.setEnabled(false);
        if (updateStatus != null) updateStatus.setText(R.string.update_checking);
        UpdateManager.check(this, (update, error) -> {
            if (updateButton != null) updateButton.setEnabled(true);
            if (error != null) {
                if (updateStatus != null) updateStatus.setText(getString(R.string.update_error, error));
                return;
            }
            prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply();
            if (update == null) {
                if (updateStatus != null) {
                    updateStatus.setText(getString(R.string.update_up_to_date, UpdateManager.currentVersion(this)));
                }
                if (manual) Toast.makeText(this, R.string.update_up_to_date_short, Toast.LENGTH_LONG).show();
                return;
            }
            if (updateStatus != null) updateStatus.setText(getString(R.string.update_available, update.version));
            String notes = update.notes != null ? update.notes.trim() : "";
            if (notes.length() > 3000) notes = notes.substring(0, 3000) + "…";
            String message = getString(R.string.update_dialog_message, update.version,
                formatBytes(update.size), notes);
            new AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download, (dialog, which) -> downloadUpdate(update))
                .show();
        });
    }

    private void downloadUpdate(UpdateManager.UpdateInfo update) {
        if (updateButton != null) updateButton.setEnabled(false);
        if (updateStatus != null) updateStatus.setText(R.string.update_downloading);
        UpdateManager.download(this, update, new UpdateManager.DownloadCallback() {
            @Override
            public void progress(long downloaded, long total) {
                if (updateStatus != null) {
                    updateStatus.setText(getString(R.string.update_download_progress,
                        formatBytes(downloaded), formatBytes(total)));
                }
            }

            @Override
            public void complete(String error) {
                if (updateButton != null) updateButton.setEnabled(true);
                if (updateStatus != null) {
                    updateStatus.setText(error == null
                        ? getString(R.string.update_ready_to_install)
                        : getString(R.string.update_error, error));
                }
            }
        });
    }

    private boolean hasStagedGamePackages() {
        File staging = new File(AppStorage.activeGameRoot(this), "to_install");
        File[] files = staging.listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        return files != null && files.length > 0;
    }

    private void stageGamePackages(Intent data) {
        List<Uri> sources = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) sources.add(uri);
            }
        } else if (data.getData() != null) {
            sources.add(data.getData());
        }
        if (sources.isEmpty()) return;

        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.install_scanning), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.install_progress_title)
            .setView(progress.label)
            .setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true)
            .show();

        new Thread(() -> {
            try {
                File staging = new File(AppStorage.activeGameRoot(this), "to_install");
                if (!staging.isDirectory() && !staging.mkdirs()) {
                    throw new IOException("Cannot create " + staging);
                }
                for (Uri source : sources) {
                    if (progress.cancelled) break;
                    String name = safeName(queryDisplayName(source));
                    if (name.isEmpty()) name = "source.bin";
                    File destination = uniqueFile(staging, name);
                    File temporary = new File(destination.getPath() + ".part");
                    try {
                        try (InputStream input = openSourceStream(source)) {
                            copyStreamToFile(input, temporary, progress);
                        }
                        if (progress.cancelled) {
                            Files.deleteIfExists(temporary.toPath());
                            break;
                        }
                        Files.move(temporary.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temporary.toPath());
                    }
                }
                finishInstall(progress, progress.cancelled
                    ? getString(R.string.install_cancelled)
                    : getString(R.string.install_done_iso_staged), !progress.cancelled);
            } catch (Exception exception) {
                String reason = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
                finishInstall(progress, getString(R.string.error_install_failed, reason), false);
            }
        }, "iso-stager").start();
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

    /** A settings section's content panel. No title of its own - the sidebar item
     *  that opens it already names the section. */
    private LinearLayout panel() {
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(20), dp(20), dp(20));
        panel.setBackgroundResource(R.drawable.bg_card_dark);
        return panel;
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

    private void applyDriverPresetToLauncher() {
        if (driverSpinner == null || renderSpinner == null) return;
        boolean experimentalA725 = driverSpinner.getSelectedItemPosition() == DRIVER_EXPERIMENTAL_A725;
        if (experimentalA725) renderSpinner.setSelection(RENDER_MODE_SYSMEM);
        renderSpinner.setEnabled(!experimentalA725);
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
        button.setTextColor(Color.rgb(225, 225, 232));
        button.setBackgroundResource(R.drawable.bg_button_dark);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(225, 225, 232));
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
