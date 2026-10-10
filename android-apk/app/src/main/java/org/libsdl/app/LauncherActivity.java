package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
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
import java.util.function.Consumer;

/** Lightweight pre-flight screen. It deliberately does not load SDL or Vulkan. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_DRIVER = 1001;
    private static final int REQUEST_GAME_ZIP = 1002;
    private static final int REQUEST_GAME_TREE = 1003;
    private static final int REQUEST_MOD_ZIP = 1004;
    private static final int REQUEST_MOD_TREE = 1005;
    private static final int REQUEST_GAME_PACKAGES = 1006;
    private static final int DRIVER_EXPERIMENTAL_A725 = 4;
    private static final int DRIVER_ADRENO_8XX = 5;
    private static final int DRIVER_IMPORTED = 6;
    private static final int RENDER_MODE_SYSMEM = 2;
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String[] DRIVER_VALUES = {
        "Auto", "System", "Bundled", "Vauzi710", "ExperimentalA725", "Adreno8xx", "Imported"
    };
    private static final String[] RENDER_MODE_VALUES = {"Auto", "GMEM", "Sysmem"};
    // Must match CONFIG_DEFINE_ENUM_TEMPLATE(EAndroidAudioPreset) in UnleashedRecomp/user/config.cpp.
    private static final String[] AUDIO_PRESET_VALUES = {"Default", "AYN", "LowLatency"};
    private static final String[] DLC_DIRECTORIES = {
        "Apotos & Shamar Adventure Pack", "Chun-nan Adventure Pack",
        "Empire City & Adabat Adventure Pack", "Holoska Adventure Pack",
        "Mazuri Adventure Pack", "Spagonia Adventure Pack"
    };

    // Settings sections, in rail order. The index is remembered across launches.
    private static final int SECTION_FILES = 0;
    private static final int SECTION_GRAPHICS = 1;
    private static final int SECTION_AUDIO = 2;
    private static final int SECTION_CONTROLS = 3;
    private static final int SECTION_MODS = 4;
    private static final int SECTION_UPDATES = 5;
    private static final int SECTION_ADVANCED = 6;

    // Dark palette shared with the game's look; the window background is in LauncherTheme.
    private static final int COLOR_PANE = 0xFF181B20;
    private static final int COLOR_RAIL = 0xFF15181C;
    private static final int COLOR_DIVIDER = 0xFF2A2E35;
    private static final int COLOR_BUTTON = 0xFF1F232A;
    private static final int COLOR_TEXT = 0xFFECEEF1;
    private static final int COLOR_TEXT_SECONDARY = 0xFFA3A9B3;
    private static final int COLOR_TEXT_MUTED = 0xFF7A808A;
    private static final int COLOR_ACCENT = 0xFFE69B19;
    private static final int COLOR_ACCENT_TINT = 0x33E69B19;
    private static final int COLOR_ON_ACCENT = 0xFF2A1700;
    private static final int COLOR_SUCCESS = 0xFF7BD88F;
    private static final int COLOR_SUCCESS_BG = 0xFF1E3A26;
    private static final int COLOR_WARNING = 0xFFF2C46D;
    private static final int COLOR_WARNING_BG = 0xFF3D3015;
    private static final int COLOR_DANGER = 0xFFF08A80;
    private static final int COLOR_DANGER_BG = 0xFF42201D;
    private static final int COLOR_NEUTRAL_BG = 0xFF262A31;
    private static final int COLOR_INFO = 0xFF8EC2F5;
    private static final int COLOR_INFO_BG = 0xFF1C2F45;

    private TextView installStatus;
    private TextView driverStatus;
    private TextView diagnosticsStatus;
    private TextView updateStatus;
    private Button playButton;
    private Button updateButton;
    private Spinner driverSpinner;
    private Spinner renderSpinner;
    private Spinner audioPresetSpinner;
    private CompoundButton skipIntro;
    private CompoundButton validation;
    private CompoundButton gfxCapture;
    private CompoundButton forceBc;
    private SharedPreferences prefs;
    private InstallState lastInstallState;
    private TextView storageStatus;
    private boolean fileOperation;

    // Home pane.
    private LinearLayout homeContent;
    private LinearLayout statusChips;
    private TextView installSummary;
    private TextView driverSummary;
    private TextView audioSummary;

    // Settings pane.
    private TextView updateBanner;
    private UpdateManager.UpdateInfo pendingUpdate;
    private ScrollView sectionScroll;
    private LinearLayout sectionContent;
    private final List<View> sections = new ArrayList<>();
    private final List<TextView> railItems = new ArrayList<>();

    private static final class InstallState {
        final boolean ready;
        final String message;
        final int dlcCount;
        InstallState(boolean ready, String message) {
            this(ready, message, 0);
        }
        InstallState(boolean ready, String message, int dlcCount) {
            this.ready = ready;
            this.message = message;
            this.dlcCount = dlcCount;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getPreferences(MODE_PRIVATE);
        setContentView(buildPage());
        ImmersiveWindow.hideSystemBars(this);
        loadSettings();
        maybeCheckForUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateManager.resumePendingInstall(this);
        refreshStatuses();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveWindow.hideSystemBars(this);
    }

    /**
     * Landscape two-pane launcher: the home pane (status, Play, quick actions) stays
     * visible, and the settings pane shows one section at a time next to a section rail.
     * Every widget is created up front, so loadSettings()/saveSettings() never see a
     * missing view whichever section is selected.
     */
    private View buildPage() {
        LinearLayout root = row();
        root.setGravity(Gravity.NO_GRAVITY);
        root.setBaselineAligned(false);
        root.addView(buildHomePane(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 38f));
        root.addView(divider(), new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildSettingsPane(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 62f));

        // The window extends under the display cutout. Pad the outer panes instead of the
        // root so each pane's own background reaches the screen edge around the camera.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.view.DisplayCutout cutout = insets.getDisplayCutout();
            int left = cutout != null ? cutout.getSafeInsetLeft() : 0;
            int top = cutout != null ? cutout.getSafeInsetTop() : 0;
            int right = cutout != null ? cutout.getSafeInsetRight() : 0;
            int bottom = cutout != null ? cutout.getSafeInsetBottom() : 0;
            homeContent.setPadding(dp(20) + left, dp(18) + top, dp(20), dp(14) + bottom);
            sectionContent.setPadding(dp(18), dp(14) + top, dp(18) + right, dp(18) + bottom);
            return insets;
        });
        return root;
    }

    private View buildHomePane() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_PANE);
        LinearLayout pane = column();
        homeContent = pane;
        pane.setPadding(dp(20), dp(18), dp(20), dp(14));
        scroll.addView(pane, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        pane.addView(text(getString(R.string.launcher_title), 24, true));
        TextView version = text("v" + UpdateManager.currentVersion(this), 13, false);
        version.setTextColor(COLOR_TEXT_MUTED);
        pane.addView(version);

        statusChips = row();
        statusChips.setPadding(0, dp(12), 0, 0);
        pane.addView(statusChips);

        installSummary = text("", 13, false);
        installSummary.setPadding(0, dp(8), 0, 0);
        installSummary.setMaxLines(4);
        installSummary.setEllipsize(TextUtils.TruncateAt.END);
        installSummary.setOnClickListener(view -> selectSection(SECTION_FILES));
        pane.addView(installSummary);

        driverSummary = summaryText(dp(10));
        driverSummary.setOnClickListener(view -> selectSection(SECTION_GRAPHICS));
        pane.addView(driverSummary);
        audioSummary = summaryText(dp(2));
        audioSummary.setOnClickListener(view -> selectSection(SECTION_AUDIO));
        pane.addView(audioSummary);

        pane.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        playButton = primaryButton(R.string.launcher_play, view -> launchGame(false));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        playParams.topMargin = dp(14);
        pane.addView(playButton, playParams);

        LinearLayout quick = row();
        quick.setPadding(0, dp(8), 0, 0);
        quick.addView(button(R.string.launcher_controls, view -> launchLayoutEditor()), spaced(0, 4));
        quick.addView(button(R.string.launcher_mods,
            view -> startActivity(new Intent(this, ModManagerActivity.class))), spaced(4, 4));
        quick.addView(button(R.string.launcher_quick_files, view -> openFiles("game")), spaced(4, 0));
        pane.addView(quick);

        TextView exit = text(getString(R.string.launcher_exit), 13, false);
        exit.setTextColor(COLOR_TEXT_MUTED);
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(0, dp(10), 0, dp(2));
        exit.setOnClickListener(view -> exitApp());
        pane.addView(exit, matchWrap());
        return scroll;
    }

    private View buildSettingsPane() {
        LinearLayout pane = row();
        pane.setGravity(Gravity.NO_GRAVITY);
        pane.setBaselineAligned(false);

        ScrollView railScroll = new ScrollView(this);
        railScroll.setBackgroundColor(COLOR_RAIL);
        LinearLayout rail = column();
        rail.setPadding(dp(8), dp(12), dp(8), dp(12));
        railScroll.addView(rail);
        pane.addView(railScroll, new LinearLayout.LayoutParams(dp(136), ViewGroup.LayoutParams.MATCH_PARENT));
        pane.addView(divider(), new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        sectionScroll = new ScrollView(this);
        LinearLayout content = column();
        sectionContent = content;
        content.setPadding(dp(18), dp(14), dp(18), dp(18));
        sectionScroll.addView(content);
        pane.addView(sectionScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        updateBanner = text("", 13, false);
        updateBanner.setTextColor(COLOR_INFO);
        updateBanner.setBackground(pressable(rounded(COLOR_INFO_BG, 0, 8)));
        updateBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(view -> {
            if (pendingUpdate != null) showUpdateDialog(pendingUpdate);
        });
        LinearLayout.LayoutParams bannerParams = matchWrap();
        bannerParams.bottomMargin = dp(12);
        content.addView(updateBanner, bannerParams);

        addSection(rail, content, R.string.launcher_game_files, R.string.launcher_game_files, this::buildFilesSection);
        addSection(rail, content, R.string.launcher_section_graphics, R.string.launcher_graphics, this::buildGraphicsSection);
        addSection(rail, content, R.string.launcher_audio, R.string.launcher_audio, this::buildAudioSection);
        addSection(rail, content, R.string.launcher_controls, R.string.launcher_controls, this::buildControlsSection);
        addSection(rail, content, R.string.launcher_mods, R.string.launcher_mods, this::buildModsSection);
        addSection(rail, content, R.string.launcher_section_updates, R.string.launcher_updates, this::buildUpdatesSection);
        addSection(rail, content, R.string.launcher_section_advanced, R.string.launcher_debug, this::buildAdvancedSection);
        selectSection(prefs.getInt("launcher_section", SECTION_FILES));
        return pane;
    }

    private void buildFilesSection(LinearLayout section) {
        installStatus = statusText();
        section.addView(installStatus);
        buttonGrid(section,
            button(R.string.launcher_install_game, view -> chooseInstallSource(true)),
            button(R.string.launcher_open_files, view -> openFiles("game")),
            button(R.string.launcher_recheck, view -> refreshStatuses()),
            button(R.string.launcher_open_saves, view -> openFiles("save")));
        storageStatus = statusText();
        storageStatus.setPadding(0, dp(16), 0, 0);
        section.addView(storageStatus);
        buttonGrid(section, button(R.string.storage_choose, view -> chooseStorage()));
    }

    private void buildGraphicsSection(LinearLayout section) {
        driverStatus = statusText();
        section.addView(driverStatus);
        driverSpinner = spinner(R.array.driver_labels);
        settingRow(section, R.string.launcher_driver, driverSpinner);
        renderSpinner = spinner(R.array.render_mode_labels);
        settingRow(section, R.string.launcher_render_mode, renderSpinner);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyDriverPresetToLauncher();
                updateSummaries();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                applyDriverPresetToLauncher();
            }
        });
        buttonGrid(section,
            button(R.string.launcher_import_driver, view -> chooseDriver()),
            button(R.string.launcher_driver_folder, view -> openFiles("transfer")));
    }

    private void buildAudioSection(LinearLayout section) {
        audioPresetSpinner = spinner(R.array.audio_preset_labels);
        settingRow(section, R.string.launcher_audio_preset, audioPresetSpinner);
        audioPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSummaries();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        section.addView(hint(getString(R.string.launcher_audio_preset_hint)));
    }

    // The runtime touch settings (on-screen controls, camera and stick) live in the
    // in-game options menu; only the layout editor stays here as it launches the game
    // renderer.
    private void buildControlsSection(LinearLayout section) {
        section.addView(hint(getString(R.string.launcher_controls_moved)));
        buttonGrid(section, button(R.string.launcher_edit_layout, view -> launchLayoutEditor()));
    }

    private void buildModsSection(LinearLayout section) {
        section.addView(hint(getString(R.string.launcher_mods_summary)));
        buttonGrid(section,
            button(R.string.launcher_manage_mods, view -> startActivity(new Intent(this, ModManagerActivity.class))),
            button(R.string.launcher_install_mod, view -> chooseInstallSource(false)));
    }

    private void buildUpdatesSection(LinearLayout section) {
        updateStatus = statusText();
        updateStatus.setText(getString(R.string.update_current_version, UpdateManager.currentVersion(this)));
        section.addView(updateStatus);
        updateButton = button(R.string.update_check, view -> checkForUpdates(true));
        buttonGrid(section, updateButton);
    }

    private void buildAdvancedSection(LinearLayout section) {
        skipIntro = switchRow(section, R.string.launcher_skip_intro);
        validation = switchRow(section, R.string.launcher_validation);
        gfxCapture = switchRow(section, R.string.launcher_gfx_capture);
        forceBc = switchRow(section, R.string.launcher_force_bc);
        diagnosticsStatus = statusText();
        diagnosticsStatus.setPadding(0, dp(12), 0, 0);
        section.addView(diagnosticsStatus);
        buttonGrid(section, button(R.string.launcher_open_logs, view -> openFiles("transfer")));
    }

    private void addSection(LinearLayout rail, LinearLayout content, int railLabelId, int titleId,
            Consumer<LinearLayout> builder) {
        final int index = sections.size();
        LinearLayout section = column();
        TextView title = text(getString(titleId), 18, true);
        title.setPadding(0, 0, 0, dp(10));
        section.addView(title);
        builder.accept(section);
        content.addView(section, matchWrap());
        sections.add(section);

        TextView item = text(getString(railLabelId), 14, false);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setOnClickListener(view -> selectSection(index));
        LinearLayout.LayoutParams itemParams = matchWrap();
        itemParams.bottomMargin = dp(2);
        rail.addView(item, itemParams);
        railItems.add(item);
    }

    private void selectSection(int index) {
        if (index < 0 || index >= sections.size()) index = SECTION_FILES;
        for (int i = 0; i < sections.size(); i++) {
            boolean selected = i == index;
            sections.get(i).setVisibility(selected ? View.VISIBLE : View.GONE);
            TextView item = railItems.get(i);
            item.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT_SECONDARY);
            item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            item.setBackground(pressable(rounded(selected ? COLOR_ACCENT_TINT : 0, 0, 8)));
        }
        if (sectionScroll != null) sectionScroll.scrollTo(0, 0);
        prefs.edit().putInt("launcher_section", index).apply();
    }

    private void updateSummaries() {
        if (driverSummary == null || driverSpinner == null || audioPresetSpinner == null) return;
        driverSummary.setText(getString(R.string.launcher_summary_driver, driverSpinner.getSelectedItem()));
        audioSummary.setText(getString(R.string.launcher_summary_audio, audioPresetSpinner.getSelectedItem()));
    }

    private void exitApp() {
        // Back/Home keep a started game alive in this process, and SDL cannot boot a
        // second session in it, so leaving with a running game means ending the process.
        if (!SDLActivity.isGameStorageLocked()) {
            finishAndRemoveTask();
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage(R.string.launcher_exit_running)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.launcher_exit, (dialog, which) -> {
                finishAndRemoveTask();
                android.os.Process.killProcess(android.os.Process.myPid());
            })
            .show();
    }

    private void refreshStatuses() {
        File currentRoot = AppStorage.activeGameRoot(this);
        storageStatus.setText(getString(R.string.storage_current, currentRoot));
        lastInstallState = inspectInstallation();
        boolean stagedInstall = hasStagedGamePackages();
        installStatus.setText(stagedInstall
            ? getString(R.string.launcher_install_staged)
            : lastInstallState.message);
        installStatus.setTextColor(lastInstallState.ready ? COLOR_SUCCESS
            : stagedInstall ? COLOR_WARNING : COLOR_DANGER);
        boolean playable = lastInstallState.ready || stagedInstall;
        playButton.setEnabled(playable);
        playButton.setAlpha(playable ? 1f : 0.45f);

        statusChips.removeAllViews();
        if (lastInstallState.ready) {
            statusChips.addView(chip(getString(R.string.launcher_chip_game_ready), COLOR_SUCCESS, COLOR_SUCCESS_BG));
            boolean allDlc = lastInstallState.dlcCount == DLC_DIRECTORIES.length;
            statusChips.addView(chip(getString(R.string.launcher_chip_dlc, lastInstallState.dlcCount,
                DLC_DIRECTORIES.length), allDlc ? COLOR_SUCCESS : COLOR_TEXT_SECONDARY,
                allDlc ? COLOR_SUCCESS_BG : COLOR_NEUTRAL_BG));
        } else if (stagedInstall) {
            statusChips.addView(chip(getString(R.string.launcher_chip_staged), COLOR_WARNING, COLOR_WARNING_BG));
        } else {
            statusChips.addView(chip(getString(R.string.launcher_chip_game_missing), COLOR_DANGER, COLOR_DANGER_BG));
        }
        // The home pane repeats the full explanation only when something needs action.
        installSummary.setVisibility(lastInstallState.ready ? View.GONE : View.VISIBLE);
        installSummary.setText(installStatus.getText());
        installSummary.setTextColor(stagedInstall ? COLOR_WARNING : COLOR_DANGER);
        updateSummaries();

        File installedMarker = new File(getFilesDir(), "turnip/last_imported_driver.txt");
        File recoveryMarker = new File(getFilesDir(), "turnip/vulkan_startup_state.txt");
        int pending = 0;
        for (File importDir : AppStorage.driverImportDirs(this)) {
            pending += countDriverPackages(importDir);
        }
        String imported = readFirstLine(installedMarker);
        // Written by the native importer for a package whose meta.json needs a newer Android.
        List<String> rejected = readLines(new File(getFilesDir(), "turnip/rejected_driver.txt"));
        if (recoveryMarker.isFile()) {
            driverStatus.setText(R.string.launcher_driver_recovery);
            driverStatus.setTextColor(COLOR_DANGER);
        } else if (rejected.size() >= 2) {
            driverStatus.setText(getString(R.string.launcher_driver_rejected, rejected.get(0), rejected.get(1),
                android.os.Build.VERSION.SDK_INT));
            driverStatus.setTextColor(COLOR_WARNING);
        } else if (pending > 0) {
            driverStatus.setText(getString(R.string.launcher_driver_pending, pending));
            driverStatus.setTextColor(COLOR_TEXT_SECONDARY);
        } else if (!imported.isEmpty()) {
            driverStatus.setText(getString(R.string.launcher_driver_installed, imported));
            driverStatus.setTextColor(COLOR_TEXT_SECONDARY);
        } else {
            driverStatus.setText(R.string.launcher_driver_builtin);
            driverStatus.setTextColor(COLOR_TEXT_SECONDARY);
        }

        File log = new File(AppStorage.transferRoot(this), "log.txt");
        diagnosticsStatus.setText(log.isFile()
            ? getString(R.string.launcher_log_found, formatBytes(log.length()))
            : getString(R.string.launcher_log_missing));
    }

    private InstallState inspectInstallation() {
        File root = AppStorage.activeGameRoot(this);
        try {
            AppStorage.validateRoot(this, root);
        } catch (IOException exception) {
            return new InstallState(false, FileErrorMessages.describe(this, exception));
        }
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
            : getString(R.string.game_ready_missing_dlc, dlc, DLC_DIRECTORIES.length), dlc);
    }

    private void loadSettings() {
        Map<String, String> config = readConfig(AppStorage.configFile(this));
        String configuredDriver = config.get("Video.VulkanDriver");
        // 0.5.3 shipped a separate "ExperimentalA8xx" profile; it is superseded by Adreno8xx.
        if (configuredDriver != null && configuredDriver.replace("\"", "").trim().equalsIgnoreCase("ExperimentalA8xx"))
            configuredDriver = "Adreno8xx";
        select(driverSpinner, configuredDriver, DRIVER_VALUES);
        select(renderSpinner, config.get("Video.RenderMode"), RENDER_MODE_VALUES);
        select(audioPresetSpinner, config.get("Audio.AndroidAudioPreset"), AUDIO_PRESET_VALUES);
        applyDriverPresetToLauncher();
        skipIntro.setChecked(Boolean.parseBoolean(config.get("Codes.SkipIntroLogos")));
        validation.setChecked(new File(getFilesDir(), "turnip/vk_layer_settings.txt").isFile());
        gfxCapture.setChecked(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt").isFile());
        forceBc.setChecked(new File(getFilesDir(), "force_bc.txt").isFile());
    }

    private boolean saveSettings() {
        applyDriverPresetToLauncher();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("Video.VulkanDriver", quote(DRIVER_VALUES[driverSpinner.getSelectedItemPosition()]));
        values.put("Video.RenderMode", quote(RENDER_MODE_VALUES[renderSpinner.getSelectedItemPosition()]));
        values.put("Audio.AndroidAudioPreset", quote(AUDIO_PRESET_VALUES[audioPresetSpinner.getSelectedItemPosition()]));
        values.put("Codes.SkipIntroLogos", Boolean.toString(skipIntro.isChecked()));
        try {
            AppStorage.validateRoot(this, AppStorage.activeGameRoot(this));
            patchConfig(AppStorage.configFile(this), values);
            setMarker(new File(getFilesDir(), "turnip/vk_layer_settings.txt"), validation.isChecked(),
                "khronos_validation.enables = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT\n");
            setMarker(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt"), gfxCapture.isChecked(), "");
            setMarker(new File(getFilesDir(), "force_bc.txt"), forceBc.isChecked(), "");
            return true;
        } catch (IOException exception) {
            showError(getString(R.string.error_settings_save, FileErrorMessages.describe(this, exception)));
            return false;
        }
    }

    private void launchGame(boolean editControls) {
        if (fileOperation) { showError(getString(R.string.storage_busy)); return; }
        if (!saveSettings()) return;
        InstallState current = inspectInstallation();
        if (!current.ready && !hasStagedGamePackages()) {
            showError(current.message);
            refreshStatuses();
            return;
        }
        if (prepareStagedArchives(editControls)) return;
        // Play clears a marker left by an editor request that never reached the game,
        // so it cannot open the editor instead of the game on the next cold launch.
        try {
            setMarker(new File(AppStorage.activeGameRoot(this), "touch_layout_edit.txt"), editControls, "1\n");
        } catch (IOException exception) {
            if (editControls) {
                showError(getString(R.string.error_layout_editor, FileErrorMessages.describe(this, exception)));
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

    /** Recover ZIPs left in to_install by versions that only copied the archive. */
    private boolean prepareStagedArchives(boolean editControls) {
        File staging = new File(AppStorage.activeGameRoot(this), "to_install");
        File[] archives = staging.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (archives == null || archives.length == 0) return false;
        fileOperation = true;
        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.install_scanning), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this).setTitle(R.string.install_progress_title)
            .setView(progress.label).setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true).show();
        new Thread(() -> {
            try {
                for (File archive : archives) {
                    File batch = new File(staging, "import-" + java.util.UUID.randomUUID());
                    File temporary = new File(batch.getPath() + ".part");
                    try {
                        try (InputStream input = new FileInputStream(archive)) {
                            progress.files += InstallerArchive.extract(input, temporary, () -> progress.cancelled,
                                bytes -> progress.bytes += bytes);
                        }
                        Files.move(temporary.toPath(), batch.toPath());
                        try { Files.delete(archive.toPath()); }
                        catch (IOException exception) { deleteImportBatch(batch); throw exception; }
                    } finally { deleteImportBatch(temporary); }
                }
                runOnUiThread(() -> {
                    fileOperation = false;
                    progress.dialog.dismiss();
                    launchGame(editControls);
                });
            } catch (Exception exception) {
                finishInstall(progress, getString(R.string.error_install_failed, FileErrorMessages.describe(this, exception)), false);
            }
        }, "staged-zip-recovery").start();
        return true;
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
            if (input == null) throw new LocalizedIOException("error_io_read", uri);
            byte[] buffer = new byte[64 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new LocalizedIOException("error_file_empty", null);
            driverSpinner.setSelection(DRIVER_IMPORTED);
            Toast.makeText(this, getString(R.string.driver_imported, target.getName()), Toast.LENGTH_LONG).show();
            refreshStatuses();
        } catch (IOException exception) {
            target.delete();
            showError(getString(R.string.error_driver_copy, FileErrorMessages.describe(this, exception)));
        }
    }

    // ------------------------------------------------------------------
    // Game files / mod installer: copy from a user-picked ZIP archive or
    // folder into the game root, locating the content root automatically.
    // ------------------------------------------------------------------

    private void chooseInstallSource(boolean gameFiles) {
        if (fileOperation || SDLActivity.isGameStorageLocked()) {
            showError(getString(R.string.storage_restart));
            return;
        }
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
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_checking);
        UpdateManager.check(this, (update, error) -> {
            updateButton.setEnabled(true);
            if (error != null) {
                updateStatus.setText(getString(R.string.update_error, error));
                return;
            }
            prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply();
            if (update == null) {
                updateStatus.setText(getString(R.string.update_up_to_date, UpdateManager.currentVersion(this)));
                if (manual) Toast.makeText(this, R.string.update_up_to_date_short, Toast.LENGTH_LONG).show();
                return;
            }
            updateStatus.setText(getString(R.string.update_available, update.version));
            // Background checks only raise the banner; a manual check opens the dialog.
            pendingUpdate = update;
            updateBanner.setText(getString(R.string.update_available, update.version) + "  " +
                getString(R.string.update_download) + " ›");
            updateBanner.setVisibility(View.VISIBLE);
            if (manual) showUpdateDialog(update);
        });
    }

    private void showUpdateDialog(UpdateManager.UpdateInfo update) {
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
    }

    private void downloadUpdate(UpdateManager.UpdateInfo update) {
        // Progress is reported in the Updates section, so show it there.
        updateBanner.setVisibility(View.GONE);
        selectSection(SECTION_UPDATES);
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_downloading);
        UpdateManager.download(this, update, new UpdateManager.DownloadCallback() {
            @Override
            public void progress(long downloaded, long total) {
                updateStatus.setText(getString(R.string.update_download_progress,
                    formatBytes(downloaded), formatBytes(total)));
            }

            @Override
            public void complete(String error) {
                updateButton.setEnabled(true);
                updateStatus.setText(error == null
                    ? getString(R.string.update_ready_to_install)
                    : getString(R.string.update_error, error));
            }
        });
    }

    private boolean hasStagedGamePackages() {
        File staging = new File(AppStorage.activeGameRoot(this), "to_install");
        File[] files = staging.listFiles(file -> !file.getName().endsWith(".part") &&
            (file.isFile() || (file.isDirectory() && file.list() != null && file.list().length > 0)));
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
        fileOperation = true;

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
                    throw new LocalizedIOException("error_io_create", staging);
                }
                for (Uri source : sources) {
                    if (progress.cancelled) break;
                    String name = safeName(queryDisplayName(source));
                    if (name.isEmpty()) name = "source.bin";
                    if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        File batch = new File(staging, "import-" + java.util.UUID.randomUUID());
                        File temporaryBatch = new File(batch.getPath() + ".part");
                        try {
                            try (InputStream input = openSourceStream(source)) {
                                progress.files += InstallerArchive.extract(input, temporaryBatch,
                                    () -> progress.cancelled, bytes -> {
                                        long previous = progress.bytes;
                                        progress.bytes += bytes;
                                        if (previous / (16 * 1024 * 1024) != progress.bytes / (16 * 1024 * 1024)) publishProgress(progress);
                                    });
                            }
                            if (!progress.cancelled) Files.move(temporaryBatch.toPath(), batch.toPath());
                        } finally {
                            deleteImportBatch(temporaryBatch);
                        }
                        continue;
                    }
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
                String reason = FileErrorMessages.describe(this, exception);
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
        fileOperation = true;
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
            File temporaryBatch = null;
            try {
                List<SourceEntry> entries = isZip ? listZipEntries(source) : listTreeEntries(source);
                Map<SourceEntry, File> plan = gameFiles
                    ? planGameInstall(entries)
                    : planModInstall(entries, fallbackModName);
                File stagedBatch = null;
                if (gameFiles && plan.isEmpty()) {
                    stagedBatch = new File(new File(AppStorage.activeGameRoot(this), "to_install"),
                        "import-" + java.util.UUID.randomUUID());
                    temporaryBatch = new File(stagedBatch.getPath() + ".part");
                    plan = planStagedInstall(entries, temporaryBatch);
                }
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
                    if (stagedBatch != null) Files.move(temporaryBatch.toPath(), stagedBatch.toPath());
                    finishInstall(progress, gameFiles
                        ? getString(stagedBatch != null ? R.string.install_done_iso_staged : R.string.install_done_game)
                        : getString(R.string.install_done_mod, modCount), true);
                }
            } catch (Exception exception) {
                String reason = FileErrorMessages.describe(this, exception);
                finishInstall(progress, getString(R.string.error_install_failed, reason), false);
            } finally {
                if (temporaryBatch != null) deleteImportBatch(temporaryBatch);
            }
        }, "installer").start();
    }

    private void finishInstall(InstallProgress progress, String message, boolean success) {
        runOnUiThread(() -> {
            fileOperation = false;
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

    private void chooseStorage() {
        if (fileOperation) { showError(getString(R.string.storage_busy)); return; }
        if (SDLActivity.isGameStorageLocked()) { showError(getString(R.string.storage_restart)); return; }
        List<AppStorage.Choice> choices = AppStorage.storageChoices(this);
        String[] labels = new String[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            AppStorage.Choice choice = choices.get(index);
            labels[index] = choice.label + "\n" + AppStorage.choiceRoot(this, choice);
        }
        new AlertDialog.Builder(this).setTitle(R.string.storage_choose)
            .setItems(labels, (dialog, index) -> confirmStorage(choices.get(index)))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmStorage(AppStorage.Choice choice) {
        File oldRoot = AppStorage.activeGameRoot(this);
        File newRoot = AppStorage.choiceRoot(this, choice);
        try {
            AppStorage.validateRoot(this, newRoot);
            if (oldRoot.getCanonicalFile().equals(newRoot.getCanonicalFile())) {
                useStorage(choice);
                return;
            }
        } catch (IOException exception) { showError(FileErrorMessages.describe(this, exception)); return; }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(choice.label)
            .setMessage(getString(R.string.storage_confirm, newRoot))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.storage_use, (unused, which) -> useStorage(choice));
        File[] files = newRoot.listFiles();
        if (oldRoot.isDirectory() && files != null && files.length == 0) {
            dialog.setNeutralButton(R.string.storage_copy, (unused, which) -> copyStorage(oldRoot, newRoot, choice));
        }
        dialog.show();
    }

    private void useStorage(AppStorage.Choice choice) {
        try {
            AppStorage.selectStorage(this, choice);
            loadSettings();
            refreshStatuses();
        } catch (IOException exception) { showError(FileErrorMessages.describe(this, exception)); }
    }

    private void copyStorage(File oldRoot, File newRoot, AppStorage.Choice choice) {
        if (!saveSettings()) return;
        fileOperation = true;
        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.storage_copying), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this).setTitle(R.string.storage_copy)
            .setView(progress.label).setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true).show();
        new Thread(() -> {
            String error = null;
            try {
                StorageMigration.copy(oldRoot, newRoot, new StorageMigration.Progress() {
                    long lastUpdate;
                    public boolean cancelled() { return progress.cancelled; }
                    public void copied(long bytes) {
                        progress.bytes += bytes;
                        if (progress.bytes - lastUpdate >= 16 * 1024 * 1024) {
                            lastUpdate = progress.bytes;
                            runOnUiThread(() -> progress.label.setText(getString(R.string.storage_copy_progress,
                                formatBytes(progress.bytes))));
                        }
                    }
                });
                AppStorage.selectStorage(this, choice);
            } catch (Exception exception) {
                error = FileErrorMessages.describe(this, exception);
            }
            String result = error;
            runOnUiThread(() -> {
                fileOperation = false;
                progress.dialog.dismiss();
                if (result != null) showError(getString(R.string.error_install_failed, result));
                else {
                    loadSettings();
                    new AlertDialog.Builder(this).setMessage(R.string.storage_copied)
                        .setPositiveButton(android.R.string.ok, null).show();
                }
                refreshStatuses();
            });
        }, "storage-copy").start();
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

    /** Stage DLC-only dumps and package archives without requiring a base game. */
    private static Map<SourceEntry, File> planStagedInstall(List<SourceEntry> entries, File batch) {
        Map<SourceEntry, File> plan = new LinkedHashMap<>();
        for (SourceEntry entry : entries) plan.put(entry, new File(batch, entry.path));
        return plan;
    }

    private static void deleteImportBatch(File batch) {
        File[] children = batch.listFiles();
        if (children != null) for (File child : children) deleteImportBatch(child);
        batch.delete();
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
        if (input == null) throw new LocalizedIOException("error_io_read", uri);
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
            throw new LocalizedIOException("error_io_create", parent);
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

    private Spinner spinner(int arrayId) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayId,
            android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    /** One compact "label - control" line with a hairline below it. */
    private void settingRow(LinearLayout section, int labelId, View control) {
        LinearLayout line = row();
        line.setPadding(0, dp(4), 0, dp(4));
        TextView label = text(getString(labelId), 14, false);
        line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        line.addView(control, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        section.addView(line, matchWrap());
        section.addView(divider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private CompoundButton switchRow(LinearLayout section, int stringId) {
        Switch toggle = new Switch(this);
        toggle.setText(stringId);
        toggle.setTextSize(14);
        toggle.setTextColor(COLOR_TEXT);
        toggle.setPadding(0, dp(10), 0, dp(10));
        section.addView(toggle, matchWrap());
        section.addView(divider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return toggle;
    }

    /** Lays out secondary actions two per row below the section's settings. */
    private void buttonGrid(LinearLayout section, Button... buttons) {
        for (int i = 0; i < buttons.length; i += 2) {
            LinearLayout line = row();
            line.setPadding(0, dp(8), 0, 0);
            if (i + 1 < buttons.length) {
                line.addView(buttons[i], spaced(0, 4));
                line.addView(buttons[i + 1], spaced(4, 0));
            } else {
                line.addView(buttons[i], spaced(0, 0));
            }
            section.addView(line, matchWrap());
        }
    }

    private void applyDriverPresetToLauncher() {
        if (driverSpinner == null || renderSpinner == null) return;
        int driver = driverSpinner.getSelectedItemPosition();
        boolean sysmemPreset = driver == DRIVER_EXPERIMENTAL_A725 || driver == DRIVER_ADRENO_8XX;
        if (sysmemPreset) renderSpinner.setSelection(RENDER_MODE_SYSMEM);
        renderSpinner.setEnabled(!sysmemPreset);
    }

    private TextView statusText() {
        TextView status = text("", 14, false);
        status.setTextColor(COLOR_TEXT_SECONDARY);
        status.setPadding(0, 0, 0, dp(8));
        return status;
    }

    private TextView summaryText(int topPadding) {
        TextView summary = text("", 13, false);
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setPadding(0, topPadding, 0, 0);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        return summary;
    }

    private TextView hint(String value) {
        TextView hint = text(value, 13, false);
        hint.setTextColor(COLOR_TEXT_MUTED);
        hint.setPadding(0, dp(8), 0, 0);
        return hint;
    }

    private TextView chip(String value, int textColor, int background) {
        TextView chip = text(value, 12, true);
        chip.setTextColor(textColor);
        chip.setBackground(rounded(background, 0, 6));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(6);
        chip.setLayoutParams(params);
        return chip;
    }

    private Button button(int stringId, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(stringId);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(COLOR_TEXT);
        button.setStateListAnimator(null);
        button.setBackground(pressable(rounded(COLOR_BUTTON, COLOR_DIVIDER, 10)));
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setOnClickListener(listener);
        return button;
    }

    private Button primaryButton(int stringId, View.OnClickListener listener) {
        Button button = button(stringId, listener);
        button.setTextSize(20);
        button.setTextColor(COLOR_ON_ACCENT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(pressable(rounded(COLOR_ACCENT, 0, 14)));
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(COLOR_TEXT);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(COLOR_DIVIDER);
        return line;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private Drawable pressable(GradientDrawable base) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(base.getCornerRadius());
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, mask);
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

    /** Equal-width slot in a row with the given horizontal margins (dp). */
    private LinearLayout.LayoutParams spaced(int leftDp, int rightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(leftDp);
        params.rightMargin = dp(rightDp);
        return params;
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
        if (!parent.isDirectory() && !parent.mkdirs()) throw new LocalizedIOException("error_io_create", parent);
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
        if (!parent.isDirectory() && !parent.mkdirs()) throw new LocalizedIOException("error_io_create", parent);
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

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) return lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line.trim());
        } catch (IOException ignored) { }
        return lines;
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
