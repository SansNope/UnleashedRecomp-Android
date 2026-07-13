package org.libsdl.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Storage Access Framework bridge for app-owned files.
 *
 * Android 11+ deliberately hides Android/data from ordinary file managers. This provider
 * exposes the same active game root selected by native storage_android.cpp, plus the external
 * transfer directory containing driver_import, logs and GFXReconstruct captures. No broad
 * storage permission is needed and paths outside these two app-owned roots cannot be resolved.
 */
public final class UnleashedDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "UnleashedDocuments";
    private static final String ROOT_GAME = "game";
    private static final String ROOT_TRANSFER = "transfer";
    private static final String ROOT_SAVE = "save";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    };

    private static final class RootInfo {
        final String id;
        final String title;
        final String summary;
        final File directory;

        RootInfo(String id, String title, String summary, File directory) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.directory = directory;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveProjection(projection, DEFAULT_ROOT_PROJECTION));

        for (RootInfo root : getRoots()) {
            ensureDirectory(root.directory);
            MatrixCursor.RowBuilder row = result.newRow();
            addColumn(result, row, Root.COLUMN_ROOT_ID, root.id);
            addColumn(result, row, Root.COLUMN_DOCUMENT_ID, createDocumentId(root, root.directory));
            addColumn(result, row, Root.COLUMN_TITLE, root.title);
            addColumn(result, row, Root.COLUMN_SUMMARY, root.summary);
            addColumn(result, row, Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
            addColumn(result, row, Root.COLUMN_MIME_TYPES, "*/*");
            addColumn(result, row, Root.COLUMN_AVAILABLE_BYTES, root.directory.getUsableSpace());
        }

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        ResolvedDocument document = resolveDocument(documentId);
        includeDocument(result, document.root, document.file);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
        throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        ResolvedDocument parent = resolveDocument(parentDocumentId);
        if (!parent.file.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }

        File[] children = parent.file.listFiles();
        if (children == null) {
            throw new FileNotFoundException("Unable to list: " + parentDocumentId);
        }

        Arrays.sort(children, Comparator
            .comparing((File file) -> !file.isDirectory())
            .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File child : children) {
            try {
                includeDocument(result, parent.root, child);
            } catch (FileNotFoundException exception) {
                // A symlink escaping the exported root is intentionally invisible.
                Log.w(TAG, "Skipping inaccessible child " + child, exception);
            }
        }

        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return getMimeType(resolveDocument(documentId).file);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        File file = resolveDocument(documentId).file;
        if (!file.isFile()) {
            throw new FileNotFoundException("Not a file: " + documentId);
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
        throws FileNotFoundException {
        ResolvedDocument parent = resolveDocument(parentDocumentId);
        if (!parent.file.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }

        File target = buildUniqueFile(parent.file, validateDisplayName(displayName));
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mimeType)
                ? target.mkdir()
                : target.createNewFile();
            if (!created) {
                throw new IOException("create returned false");
            }
            return createDocumentId(parent.root, target);
        } catch (IOException exception) {
            throw fileNotFound("Unable to create " + target, exception);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        ResolvedDocument document = resolveDocument(documentId);
        rejectRootMutation(document);
        try {
            deleteRecursively(document.file);
        } catch (IOException exception) {
            throw fileNotFound("Unable to delete " + document.file, exception);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        ResolvedDocument document = resolveDocument(documentId);
        rejectRootMutation(document);
        File target = buildUniqueFile(document.file.getParentFile(), validateDisplayName(displayName));

        try {
            Files.move(document.file.toPath(), target.toPath());
            return createDocumentId(document.root, target);
        } catch (IOException exception) {
            throw fileNotFound("Unable to rename " + document.file, exception);
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            ResolvedDocument parent = resolveDocument(parentDocumentId);
            ResolvedDocument child = resolveDocument(documentId);
            if (!parent.root.id.equals(child.root.id)) {
                return false;
            }

            String parentPath = parent.file.getCanonicalPath();
            String childPath = child.file.getCanonicalPath();
            return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
        } catch (IOException exception) {
            return false;
        }
    }

    private List<RootInfo> getRoots() {
        File externalFiles = requireContext().getExternalFilesDir(null);
        File activeGame = AppStorage.activeGameRoot(requireContext());

        List<RootInfo> roots = new ArrayList<>();
        roots.add(new RootInfo(
            ROOT_GAME,
            requireContext().getString(R.string.documents_game_title),
            requireContext().getString(R.string.documents_game_summary),
            activeGame));

        roots.add(new RootInfo(
            ROOT_SAVE,
            requireContext().getString(R.string.documents_save_title),
            requireContext().getString(R.string.documents_save_summary),
            AppStorage.saveDir(requireContext())));

        if (externalFiles != null) {
            roots.add(new RootInfo(
                ROOT_TRANSFER,
                requireContext().getString(R.string.documents_transfer_title),
                requireContext().getString(R.string.documents_transfer_summary),
                externalFiles));
        }
        return roots;
    }

    private static final class ResolvedDocument {
        final RootInfo root;
        final File file;

        ResolvedDocument(RootInfo root, File file) {
            this.root = root;
            this.file = file;
        }
    }

    private ResolvedDocument resolveDocument(String documentId) throws FileNotFoundException {
        int separator = documentId.indexOf(':');
        if (separator < 0) {
            throw new FileNotFoundException("Malformed document id");
        }

        String rootId = documentId.substring(0, separator);
        RootInfo root = null;
        for (RootInfo candidate : getRoots()) {
            if (candidate.id.equals(rootId)) {
                root = candidate;
                break;
            }
        }
        if (root == null) {
            throw new FileNotFoundException("Unknown root: " + rootId);
        }

        String encodedPath = documentId.substring(separator + 1);
        String relativePath;
        try {
            relativePath = encodedPath.isEmpty()
                ? ""
                : new String(Base64.decode(encodedPath, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw fileNotFound("Malformed document id", exception);
        }

        try {
            File canonicalRoot = root.directory.getCanonicalFile();
            File file = relativePath.isEmpty()
                ? canonicalRoot
                : new File(canonicalRoot, relativePath).getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String filePath = file.getPath();
            if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("Document escapes its root");
            }
            if (!file.exists()) {
                throw new FileNotFoundException("Document does not exist: " + documentId);
            }
            return new ResolvedDocument(root, file);
        } catch (IOException exception) {
            throw fileNotFound("Unable to resolve document", exception);
        }
    }

    private static String createDocumentId(RootInfo root, File file) throws FileNotFoundException {
        try {
            File canonicalRoot = root.directory.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String filePath = canonicalFile.getPath();
            if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("Document escapes its root");
            }

            if (filePath.equals(rootPath)) {
                return root.id + ":";
            }
            String relativePath = filePath.substring(rootPath.length() + 1);
            String encodedPath = Base64.encodeToString(
                relativePath.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return root.id + ":" + encodedPath;
        } catch (IOException exception) {
            throw fileNotFound("Unable to create document id", exception);
        }
    }

    private static void includeDocument(MatrixCursor result, RootInfo root, File file)
        throws FileNotFoundException {
        MatrixCursor.RowBuilder row = result.newRow();
        String mimeType = getMimeType(file);
        int flags = 0;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }

        try {
            if (!file.getCanonicalFile().equals(root.directory.getCanonicalFile())) {
                flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
            }
        } catch (IOException exception) {
            throw fileNotFound("Unable to inspect " + file, exception);
        }

        addColumn(result, row, Document.COLUMN_DOCUMENT_ID, createDocumentId(root, file));
        addColumn(result, row, Document.COLUMN_DISPLAY_NAME, file.getName().isEmpty() ? root.title : file.getName());
        addColumn(result, row, Document.COLUMN_MIME_TYPE, mimeType);
        addColumn(result, row, Document.COLUMN_FLAGS, flags);
        addColumn(result, row, Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        addColumn(result, row, Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        String guessed = URLConnection.guessContentTypeFromName(file.getName());
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String validateDisplayName(String displayName) throws FileNotFoundException {
        if (displayName == null || displayName.isEmpty() || ".".equals(displayName) || "..".equals(displayName)
            || displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0 || displayName.indexOf('\0') >= 0) {
            throw new FileNotFoundException("Invalid display name");
        }
        return displayName;
    }

    private static File buildUniqueFile(File parent, String displayName) {
        File candidate = new File(parent, displayName);
        if (!candidate.exists()) {
            return candidate;
        }

        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String extension = dot > 0 ? displayName.substring(dot) : "";
        for (int index = 1; index < 10000; index++) {
            candidate = new File(parent, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(parent, base + " (copy)" + extension);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Unable to list directory");
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.delete(file.toPath());
    }

    private static void rejectRootMutation(ResolvedDocument document) throws FileNotFoundException {
        try {
            if (document.file.getCanonicalFile().equals(document.root.directory.getCanonicalFile())) {
                throw new FileNotFoundException("The provider root cannot be modified");
            }
        } catch (IOException exception) {
            throw fileNotFound("Unable to validate provider root", exception);
        }
    }

    private static void ensureDirectory(File directory) throws FileNotFoundException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new FileNotFoundException("Unable to create " + directory);
        }
    }

    private static String[] resolveProjection(String[] requested, String[] defaults) {
        return requested != null ? requested : defaults;
    }

    private static void addColumn(MatrixCursor cursor, MatrixCursor.RowBuilder row, String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) {
            row.add(column, value);
        }
    }

    private static FileNotFoundException fileNotFound(String message, Exception cause) {
        FileNotFoundException result = new FileNotFoundException(message);
        result.initCause(cause);
        return result;
    }
}
