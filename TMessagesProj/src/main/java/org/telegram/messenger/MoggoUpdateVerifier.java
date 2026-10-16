package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Moggogram: checks a downloaded update APK before it is handed to the system installer.
 * Android only compares signatures when an APK replaces the same package, so a post with an APK
 * under another package name (and our icon and name) would otherwise install next to Moggogram.
 * Accepted only if the package name matches this app and it is signed with the same certificate.
 */
public final class MoggoUpdateVerifier {

    private MoggoUpdateVerifier() {
    }

    public static boolean isGenuine(Context context, File apk) {
        try {
            if (apk == null || !apk.isFile()) return false;
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null || !context.getPackageName().equals(archive.packageName)) return false;
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            Set<String> expected = certificates(installed);
            Set<String> actual = certificates(archive);
            return !expected.isEmpty() && expected.equals(actual);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certificates(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return new HashSet<>();
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures == null) return result;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            result.add(Utilities.bytesToHex(sha256.digest(signature.toByteArray())));
        }
        return result;
    }
}
