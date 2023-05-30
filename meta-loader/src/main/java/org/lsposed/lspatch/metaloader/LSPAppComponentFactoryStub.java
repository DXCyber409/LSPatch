package org.lsposed.lspatch.metaloader;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.ServiceManager;
import android.util.JsonReader;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.lsposed.lspatch.share.Constants;

import dalvik.system.PathClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.ClassLoader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LSPAppComponentFactoryStub extends AppComponentFactory {

    private static final String TAG = "LSPatch-MetaLoader";
    private static final Map<String, String> archToLib = new HashMap<String, String>(4);

    public static byte[] dex;

    static {
        try {
            archToLib.put("arm", "armeabi-v7a");
            archToLib.put("arm64", "arm64-v8a");
            archToLib.put("x86", "x86");
            archToLib.put("x86_64", "x86_64");

            var cl = Objects.requireNonNull(LSPAppComponentFactoryStub.class.getClassLoader());
            Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
            vmInstructionSet.setAccessible(true);
            String arch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
            String libName = archToLib.get(arch);

            boolean useManager = false;
            String soPath;

            try (var is = cl.getResourceAsStream(Constants.CONFIG_ASSET_PATH);
                 var reader = new JsonReader(new InputStreamReader(is))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    var name = reader.nextName();
                    if (name.equals("useManager")) {
                        useManager = reader.nextBoolean();
                        break;
                    } else {
                        reader.skipValue();
                    }
                }
            }

            if (useManager) {
                Log.i(TAG, "Bootstrap loader from manager");
                var ipm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
                ApplicationInfo manager;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager = (ApplicationInfo) HiddenApiBypass.invoke(IPackageManager.class, ipm, "getApplicationInfo", Constants.MANAGER_PACKAGE_NAME, 0L, 0);
                } else {
                    manager = ipm.getApplicationInfo(Constants.MANAGER_PACKAGE_NAME, 0, 0);
                }
                try (var zip = new ZipFile(new File(manager.sourceDir));
                     var is = zip.getInputStream(zip.getEntry(Constants.LOADER_DEX_ASSET_PATH));
                     var os = new ByteArrayOutputStream()) {
                    transfer(is, os);
                    dex = os.toByteArray();
                }
                soPath = manager.sourceDir + "!/assets/lspatch/so/" + libName + "/liblspatch.so";
            } else {
                Log.i(TAG, "Bootstrap loader from embedment");
                try (var is = cl.getResourceAsStream(Constants.LOADER_DEX_ASSET_PATH);
                     var os = new ByteArrayOutputStream()) {
                    transfer(is, os);
                    dex = os.toByteArray();
                }
                soPath = cl.getResource("assets/lspatch/so/" + libName + "/liblspatch.so").getPath().substring(5);
            }

            if (ActivityThread.currentActivityThread() != null) {
                System.load(soPath);
            } else {
                Log.d(TAG, "Skip loading liblspatch.so for AppZygoteInit");
            }
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void transfer(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo appInfo) {
        var originalApkPath = Paths.get(appInfo.sourceDir).getParent().toString() + "/split_original.apk";
        var appClassLoader = new PathClassLoader(originalApkPath, cl);
        if (ActivityThread.currentActivityThread() != null) {
            return cl;
        } else {
            try {
                Log.d(TAG, "Calling original ZygotePreload");
                appInfo.sourceDir = originalApkPath;
				String zygotePreloadName = (String) ApplicationInfo.class.getDeclaredField("zygotePreloadName").get(appInfo);
                Class<?> originalPreload = cl.loadClass(zygotePreloadName);
                Method originalDoPreload = originalPreload.getDeclaredMethod("doPreload", ApplicationInfo.class);
                originalDoPreload.invoke(originalPreload.getDeclaredConstructor().newInstance(), appInfo);
				cl.loadClass(zygotePreloadName + "Mock");
            } catch (Throwable e) {
                Log.e(TAG, "Dump ClassLoader: " + e.getMessage());
            }
            return null;
        }
    }
}
