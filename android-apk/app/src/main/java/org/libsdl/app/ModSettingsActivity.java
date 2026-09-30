package org.libsdl.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** HMM-compatible ConfigSchemaFile editor (issue #75). */
public final class ModSettingsActivity extends Activity {
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_ROOT = "root";
    static final String EXTRA_SCHEMA = "schema";
    private static final long MAX_SCHEMA_BYTES = 1024 * 1024;
    private static final int MAX_FIELDS = 256;

    private final List<FieldBinding> fields = new ArrayList<>();
    private LinearLayout form;
    private TextView status;
    private File modRoot;
    private File configFile;

    private static final class EnumChoice {
        final String label;
        final String value;
        EnumChoice(String label, String value) {
            this.label = label;
            this.value = value;
        }
        @Override public String toString() { return label; }
    }

    private static final class FieldBinding {
        String section;
        String name;
        String type;
        Object defaultValue;
        Double min;
        Double max;
        View input;
        List<EnumChoice> choices;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 28 /* Android 9 (P) */) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }
        hideSystemBars();
        modRoot = new File(getIntent().getStringExtra(EXTRA_ROOT));
        File schema = new File(getIntent().getStringExtra(EXTRA_SCHEMA));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = text(getIntent().getStringExtra(EXTRA_TITLE), 23, true);
        page.addView(title, matchWrap());

        ScrollView scroll = new ScrollView(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        status = text("", 14, false);
        page.addView(status, matchWrap());

        Button save = new Button(this);
        save.setText(R.string.mod_settings_save);
        save.setOnClickListener(view -> save());
        page.addView(save, matchWrap());
        setContentView(page);

        try {
            loadSchema(schema);
            save.setEnabled(!fields.isEmpty());
            if (fields.isEmpty()) status.setText(R.string.mod_settings_empty);
        } catch (Exception exception) {
            save.setEnabled(false);
            status.setText(getString(R.string.mod_settings_error, readableError(exception)));
        }
    }

    private void loadSchema(File schemaFile) throws Exception {
        if (!schemaFile.isFile() || schemaFile.length() <= 0 || schemaFile.length() > MAX_SCHEMA_BYTES) {
            throw new IOException("Invalid or oversized ConfigSchemaFile");
        }
        JSONObject schema = new JSONObject(readText(schemaFile));
        configFile = resolveInsideMod(schema.optString("IniFile", "config.ini"));
        Map<String, Map<String, String>> current = readIni(configFile);
        JSONObject enums = schema.optJSONObject("Enums");
        JSONArray groups = schema.optJSONArray("Groups");
        if (groups == null) throw new IOException("Config schema has no Groups array");

        for (int groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
            JSONObject group = groups.optJSONObject(groupIndex);
            if (group == null) continue;
            String section = cleanName(group.optString("Name", "ConfigGroup"));
            String display = group.optString("DisplayName", section);
            TextView header = text(display, 19, true);
            header.setPadding(0, dp(14), 0, dp(4));
            form.addView(header, matchWrap());

            JSONArray elements = group.optJSONArray("Elements");
            if (elements == null) continue;
            for (int elementIndex = 0; elementIndex < elements.length(); elementIndex++) {
                if (fields.size() >= MAX_FIELDS) throw new IOException("Config schema has too many fields");
                JSONObject element = elements.optJSONObject(elementIndex);
                if (element == null) continue;
                addField(section, element, enums, current.get(section));
            }
        }
    }

    private void addField(String section, JSONObject element, JSONObject enums,
            Map<String, String> currentSection) throws Exception {
        FieldBinding field = new FieldBinding();
        field.section = section;
        field.name = cleanName(element.optString("Name", "ConfigName"));
        field.type = element.optString("Type", "string").toLowerCase(Locale.ROOT);
        field.defaultValue = element.has("DefaultValue") && !element.isNull("DefaultValue")
            ? element.get("DefaultValue") : "";
        field.min = optionalDouble(element, "MinValue");
        field.max = optionalDouble(element, "MaxValue");
        String saved = currentSection != null ? currentSection.get(field.name) : null;
        String value = saved != null ? saved : String.valueOf(field.defaultValue);

        TextView label = text(element.optString("DisplayName", field.name), 15, true);
        form.addView(label, matchWrap());
        String description = joinDescription(element.optJSONArray("Description"));
        if (!description.isEmpty()) {
            TextView hint = text(description, 13, false);
            hint.setPadding(0, 0, 0, dp(3));
            form.addView(hint, matchWrap());
        }

        if ("bool".equals(field.type)) {
            CheckBox box = new CheckBox(this);
            box.setChecked(Boolean.parseBoolean(value));
            field.input = box;
            form.addView(box, matchWrap());
        } else if (enums != null && enums.has(element.optString("Type", ""))) {
            JSONArray values = enums.optJSONArray(element.optString("Type", ""));
            field.choices = new ArrayList<>();
            int selected = 0;
            if (values != null) {
                for (int index = 0; index < values.length(); index++) {
                    JSONObject choice = values.optJSONObject(index);
                    if (choice == null) continue;
                    String choiceValue = String.valueOf(choice.opt("Value"));
                    field.choices.add(new EnumChoice(choice.optString("DisplayName", choiceValue), choiceValue));
                    if (choiceValue.equals(value)) selected = field.choices.size() - 1;
                }
            }
            Spinner spinner = new Spinner(this);
            ArrayAdapter<EnumChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, field.choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            if (!field.choices.isEmpty()) spinner.setSelection(selected);
            field.input = spinner;
            form.addView(spinner, matchWrap());
        } else {
            EditText edit = new EditText(this);
            edit.setText(value);
            if ("int".equals(field.type)) {
                edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else if ("float".equals(field.type)) {
                edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED |
                    InputType.TYPE_NUMBER_FLAG_DECIMAL);
            } else {
                field.type = "string";
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            }
            field.input = edit;
            form.addView(edit, matchWrap());
        }
        fields.add(field);
    }

    private void save() {
        LinkedHashMap<String, LinkedHashMap<String, String>> changes = new LinkedHashMap<>();
        try {
            for (FieldBinding field : fields) {
                String value = readField(field);
                changes.computeIfAbsent(field.section, unused -> new LinkedHashMap<>())
                    .put(field.name, value);
            }
            patchIni(configFile, changes);
            Toast.makeText(this, R.string.mod_settings_saved, Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception exception) {
            status.setText(getString(R.string.mod_settings_error, readableError(exception)));
        }
    }

    private String readField(FieldBinding field) throws IOException {
        if (field.input instanceof CheckBox) {
            return Boolean.toString(((CheckBox) field.input).isChecked());
        }
        if (field.input instanceof Spinner) {
            int index = ((Spinner) field.input).getSelectedItemPosition();
            if (field.choices == null || index < 0 || index >= field.choices.size()) {
                throw new IOException(field.name + ": no enum value selected");
            }
            return field.choices.get(index).value;
        }
        String value = ((EditText) field.input).getText().toString();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('"') >= 0) {
            throw new IOException(field.name + ": quotes and newlines are not supported");
        }
        if ("int".equals(field.type) || "float".equals(field.type)) {
            double number;
            try {
                number = Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new IOException(field.name + ": invalid number");
            }
            if ((field.min != null && number < field.min) ||
                    (field.max != null && number > field.max)) {
                throw new IOException(field.name + ": value is outside the allowed range");
            }
            if ("int".equals(field.type)) {
                if (number != Math.rint(number)) throw new IOException(field.name + ": integer required");
                return Long.toString((long) number);
            }
        }
        return value;
    }

    private File resolveInsideMod(String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) relativePath = "config.ini";
        File root = modRoot.getCanonicalFile();
        File target = new File(root, relativePath).getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        if (!target.getPath().startsWith(rootPrefix)) throw new IOException("Config path escapes the mod folder");
        return target;
    }

    private static String cleanName(String value) throws IOException {
        value = value != null ? value.trim() : "";
        if (value.isEmpty() || value.indexOf(']') >= 0 || value.indexOf('[') >= 0 ||
            value.indexOf('=') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Invalid config section or key name");
        }
        return value;
    }

    private static Double optionalDouble(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        try { return Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }

    private static String joinDescription(JSONArray values) {
        if (values == null) return "";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            String line = values.optString(index, "").trim();
            if (line.isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    private static String readText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, Map<String, String>> readIni(File file) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String section = "";
        result.put(section, new LinkedHashMap<>());
        if (!file.isFile()) return result;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                    result.computeIfAbsent(section, unused -> new LinkedHashMap<>());
                } else if (!trimmed.isEmpty() && !trimmed.startsWith(";") && !trimmed.startsWith("#")) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        String value = trimmed.substring(equals + 1).trim();
                        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        result.get(section).put(trimmed.substring(0, equals).trim(), value);
                    }
                }
            }
        } catch (IOException ignored) {
            result.clear();
        }
        return result;
    }

    private static void patchIni(File target,
            LinkedHashMap<String, LinkedHashMap<String, String>> changes) throws IOException {
        List<String> original = target.isFile()
            ? Files.readAllLines(target.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
        List<String> output = new ArrayList<>();
        Set<String> seenSections = new LinkedHashSet<>();
        Map<String, Set<String>> seenKeys = new LinkedHashMap<>();
        String section = "";

        for (String line : original) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                appendMissing(output, section, changes, seenKeys);
                section = trimmed.substring(1, trimmed.length() - 1).trim();
                seenSections.add(section);
                output.add(line);
                continue;
            }
            LinkedHashMap<String, String> sectionChanges = changes.get(section);
            int equals = trimmed.indexOf('=');
            if (sectionChanges != null && equals > 0 && !trimmed.startsWith(";") && !trimmed.startsWith("#")) {
                String key = trimmed.substring(0, equals).trim();
                if (sectionChanges.containsKey(key)) {
                    output.add(key + "=" + encodeValue(sectionChanges.get(key)));
                    seenKeys.computeIfAbsent(section, unused -> new LinkedHashSet<>()).add(key);
                    continue;
                }
            }
            output.add(line);
        }
        appendMissing(output, section, changes, seenKeys);
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : changes.entrySet()) {
            if (seenSections.contains(entry.getKey())) continue;
            if (!output.isEmpty() && !output.get(output.size() - 1).isEmpty()) output.add("");
            output.add("[" + entry.getKey() + "]");
            for (Map.Entry<String, String> value : entry.getValue().entrySet()) {
                output.add(value.getKey() + "=" + encodeValue(value.getValue()));
            }
        }
        writeAtomic(target, String.join("\n", output) + "\n");
    }

    private static void appendMissing(List<String> output, String section,
            LinkedHashMap<String, LinkedHashMap<String, String>> changes,
            Map<String, Set<String>> seenKeys) {
        LinkedHashMap<String, String> values = changes.get(section);
        if (values == null) return;
        Set<String> seen = seenKeys.get(section);
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (seen == null || !seen.contains(value.getKey())) {
                output.add(value.getKey() + "=" + encodeValue(value.getValue()));
            }
        }
    }

    private static String encodeValue(String value) {
        if (value.isEmpty()) return "\"\"";
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return "\"" + value + "\"";
        }
        return value;
    }

    private static void writeAtomic(File target, String contents) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write(contents);
            writer.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static String readableError(Exception exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value != null ? value : "");
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** See LauncherActivity.hideSystemBars(). */
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
}
