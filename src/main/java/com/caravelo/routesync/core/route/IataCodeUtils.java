package com.caravelo.routesync.core.route;

import java.util.Locale;
import java.util.regex.Pattern;

/** Station code normalisation shared by adapters (SPEC R2, R3). */
public final class IataCodeUtils {

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

    private IataCodeUtils() {
    }

    /** Trimmed, upper-case code, or {@code null} when absent or not a valid IATA code. */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return IATA.matcher(code).matches() ? code : null;
    }

    public static boolean isValid(String code) {
        return code != null && IATA.matcher(code).matches();
    }
}
