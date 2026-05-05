package com.winlator.contents;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manifest-installed PanVK / Mali Vulkan stacks (ZIP + meta.json), parallel to {@link AdrenotoolsManager}
 * but applies {@code VK_ICD_FILENAMES} and {@code LD_LIBRARY_PATH} instead of {@code ADRENOTOOLS_*}.
 *
 * <p>Expected {@code meta.json} keys:</p>
 * <ul>
 *   <li>{@code name} — install folder name (required)</li>
 *   <li>{@code driverVersion} — informational</li>
 *   <li>{@code driverStack} — optional; use {@code "panvk"} for manifest routing</li>
 *   <li>{@code icdRelativePath} — optional (default {@code share/vulkan/icd.d/panfrost_icd.aarch64.json})</li>
 *   <li>{@code libDirRelative} — optional (default {@code lib})</li>
 * </ul>
 */
public class PanVkDriverManager {

    private static final String TAG = "PanVkDriverManager";

    private final File panvkManifestDir;
    private final Context context;

    public PanVkDriverManager(Context context) {
        this.context = context;
        this.panvkManifestDir = new File(context.getFilesDir(), "contents/panvk_manifest");
        if (!panvkManifestDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            panvkManifestDir.mkdirs();
        }
    }

    public String getDriverName(String folderId) {
        try {
            File metaProfile = new File(new File(panvkManifestDir, folderId), "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            return jsonObject.getString("name");
        } catch (JSONException e) {
            return "";
        }
    }

    public String getDriverVersion(String folderId) {
        try {
            File metaProfile = new File(new File(panvkManifestDir, folderId), "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            return jsonObject.optString("driverVersion", "");
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Install from a downloaded ZIP (same structural convention as {@link AdrenotoolsManager#installDriver}).
     */
    public String installDriver(Uri driverUri) {
        File tmpDir = new File(panvkManifestDir, "tmp");
        if (tmpDir.exists()) {
            FileUtils.delete(tmpDir);
        }
        tmpDir.mkdirs();
        String name = "";

        try (InputStream is = context.getContentResolver().openInputStream(driverUri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File dstFile = new File(tmpDir, entry.getName());
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    dstFile.mkdirs();
                } else {
                    File parent = dstFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                entry = zis.getNextEntry();
            }
            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName("tmp");
                File dst = new File(panvkManifestDir, name);
                if (!dst.exists() && !name.equals("")) {
                    tmpDir.renameTo(dst);
                } else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            } else {
                Log.d(TAG, "installDriver: missing meta.json");
                FileUtils.delete(tmpDir);
            }
        } catch (IOException e) {
            Log.d(TAG, "installDriver failed", e);
            FileUtils.delete(tmpDir);
        }

        return name;
    }

    public ArrayList<String> enumerateInstalledDrivers() {
        ArrayList<String> out = new ArrayList<>();
        File[] list = panvkManifestDir.listFiles();
        if (list == null) return out;
        for (File f : list) {
            if (f.isDirectory() && new File(f, "meta.json").exists() && !"tmp".equals(f.getName())) {
                out.add(f.getName());
            }
        }
        return out;
    }

    public void removeDriver(String folderId) {
        File path = new File(panvkManifestDir, folderId);
        FileUtils.delete(path);
    }

    /**
     * Apply env for a manifest-installed driver folder id (matches {@code meta.json} {@code name}).
     */
    public void applyManifestDriver(EnvVars envVars, ImageFs imageFs, String folderId) {
        if (folderId == null || folderId.isEmpty()) return;
        File root = new File(panvkManifestDir, folderId);
        if (!root.isDirectory()) return;
        applyFromDriverRoot(envVars, imageFs, root);
    }

    /**
     * Apply env for a {@link ContentsManager} PanVK profile installed under {@code files/contents/PanVK/}.
     */
    public void applyContentProfile(Context ctx, EnvVars envVars, ImageFs imageFs, ContentProfile profile) {
        if (profile == null || profile.type != ContentProfile.ContentType.CONTENT_TYPE_PANVK) return;
        File root = ContentsManager.getInstallDir(ctx, profile);
        if (!root.isDirectory()) return;
        applyFromDriverRoot(envVars, imageFs, root);
    }

    private void applyFromDriverRoot(EnvVars envVars, ImageFs imageFs, File root) {
        File metaFile = new File(root, "meta.json");
        String icdRel = "share/vulkan/icd.d/panfrost_icd.aarch64.json";
        String libRel = "lib";
        if (metaFile.isFile()) {
            try {
                JSONObject o = new JSONObject(FileUtils.readString(metaFile));
                icdRel = o.optString("icdRelativePath", icdRel);
                libRel = o.optString("libDirRelative", libRel);
            } catch (JSONException ignored) {
            }
        }

        File icd = new File(root, icdRel.replace("/", File.separator));
        if (!icd.isFile()) {
            File icdDir = new File(root, "share/vulkan/icd.d".replace("/", File.separator));
            if (icdDir.isDirectory()) {
                File[] icds = icdDir.listFiles((dir, n) -> n.endsWith(".json"));
                if (icds != null && icds.length > 0) {
                    icd = icds[0];
                }
            }
        }
        if (icd.isFile()) {
            envVars.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
        } else {
            Log.w(TAG, "No Vulkan ICD found under " + root.getAbsolutePath());
        }

        File libDir = new File(root, libRel.replace("/", File.separator));
        String libPath = libDir.isDirectory() ? libDir.getAbsolutePath() : root.getAbsolutePath();
        String existing = envVars.get("LD_LIBRARY_PATH");
        if (existing == null || existing.isEmpty()) {
            envVars.put("LD_LIBRARY_PATH", libPath);
        } else if (!existing.contains(libPath)) {
            envVars.put("LD_LIBRARY_PATH", libPath + ":" + existing);
        }

        envVars.put("GALLIUM_DRIVER", "zink");
        envVars.put("ZINK_CONTEXT_THREADED", "1");
        envVars.put("LIBGL_KOPPER_DISABLE", "true");
        if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) {
            envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
        }
        envVars.put("vblank_mode", "0");
    }
}
