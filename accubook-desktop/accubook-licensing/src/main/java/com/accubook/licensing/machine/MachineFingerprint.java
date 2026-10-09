package com.accubook.licensing.machine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies the machine an installation is bound to.
 *
 * <p>Five weak signals, hashed, matched {@code K}-of-{@code N} (decision P3-2:
 * 3 of 5). No single value is required, so ordinary hardware and software churn
 * does not lock a customer out of their own books.
 *
 * <h2>What is deliberately not used, and why</h2>
 * <ul>
 *   <li><strong>DMI hardware serials</strong> ({@code product_uuid},
 *       {@code board_serial}). Measured: both throw
 *       {@code AccessDeniedException} for a non-root process on Linux. An
 *       accounting application must not require root, so a fingerprint that
 *       depends on them would simply fail for every Linux customer.</li>
 *   <li><strong>Enumerating every MAC address.</strong> Measured:
 *       {@code NetworkInterface.isVirtual()} reports {@code docker0} as
 *       {@code false}, so Java cannot reliably tell a virtual adapter from a
 *       real one. A fingerprint over all interfaces would change the day a
 *       customer installs Docker, a VPN, VirtualBox or WSL - punishing them for
 *       something entirely unrelated. Only the interface backing the default
 *       route is considered, and only as one signal among five.</li>
 * </ul>
 *
 * <h2>Privacy</h2>
 * Only hashes are stored or transmitted. A MAC address is personal data, and
 * there is no reason for the licence server to hold an inventory of customers'
 * hardware.
 *
 * <h2>What this is and is not</h2>
 * This makes casual copying ineffective. It is not, and cannot be, a hardware
 * lock: a determined attacker with local access can patch the binary, and the
 * threat model says so plainly. The design therefore optimises for not locking
 * out legitimate customers rather than for defeating a determined one.
 */
public final class MachineFingerprint {

    /** Signals required to agree for a match. Decision P3-2. */
    public static final int REQUIRED_MATCHES = 3;

    /** Signals collected. */
    public static final int TOTAL_SIGNALS = 5;

    private final List<String> hashes;

    private MachineFingerprint(List<String> hashes) {
        this.hashes = List.copyOf(hashes);
    }

    /** Collect the signals available on this machine. */
    public static MachineFingerprint collect(Path installUuidFile) {
        List<String> signals = new ArrayList<>();
        signals.add(hash("install", installUuid(installUuidFile)));
        signals.add(hash("machine-id", osMachineId().orElse("")));
        signals.add(hash("cpu", cpuSignature()));
        signals.add(hash("mac", primaryMacAddress().orElse("")));
        signals.add(hash("os", osSignature()));
        return new MachineFingerprint(signals);
    }

    /** Rebuild from stored hashes, e.g. those inside an entitlement token. */
    public static MachineFingerprint of(List<String> hashes) {
        return new MachineFingerprint(hashes);
    }

    /** The hashed signals, in a stable order. */
    public List<String> hashes() {
        return hashes;
    }

    /** How many signals this fingerprint shares with {@code other}. */
    public int matchCount(MachineFingerprint other) {
        int matches = 0;
        int limit = Math.min(hashes.size(), other.hashes.size());
        for (int i = 0; i < limit; i++) {
            String mine = hashes.get(i);
            String theirs = other.hashes.get(i);
            // An empty hash means the signal was unavailable on one side, which
            // is not evidence of a different machine - so it is not a match, but
            // it is not counted against either.
            if (!mine.isEmpty() && mine.equals(theirs)) {
                matches++;
            }
        }
        return matches;
    }

    /** True when enough signals agree to treat this as the same machine. */
    public boolean matches(MachineFingerprint other) {
        return matchCount(other) >= REQUIRED_MATCHES;
    }

    /**
     * The app's own identifier, created on first run.
     *
     * <p>The most stable signal by far: it survives hardware changes, network
     * changes and OS updates, and only disappears on a reinstall or a wipe.
     */
    static String installUuid(Path file) {
        try {
            if (Files.exists(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            Files.createDirectories(file.getParent());
            String created = java.util.UUID.randomUUID().toString();
            Files.writeString(file, created, StandardCharsets.UTF_8);
            return created;
        } catch (IOException e) {
            // Cannot persist one - fall back to an empty signal rather than a
            // random per-run value, which would guarantee a mismatch every time.
            return "";
        }
    }

    /** The operating system's own machine identifier, where one exists. */
    static Optional<String> osMachineId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            for (String candidate : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                try {
                    String value = Files.readString(Path.of(candidate), StandardCharsets.UTF_8).strip();
                    if (!value.isEmpty()) {
                        return Optional.of(value);
                    }
                } catch (IOException ignored) {
                    // Try the next candidate.
                }
            }
            return Optional.empty();
        }
        // Windows reads MachineGuid from the registry and macOS reads
        // IOPlatformUUID; both are available without elevation. Wired up with
        // the platform providers rather than here, so this class stays testable
        // without a real machine.
        return Optional.ofNullable(System.getProperty("accubook.machine.id"));
    }

    static String cpuSignature() {
        return System.getProperty("os.arch", "") + "|" + Runtime.getRuntime().availableProcessors();
    }

    static String osSignature() {
        return System.getProperty("os.name", "") + "|" + System.getProperty("os.version", "");
    }

    /**
     * The MAC of the interface carrying the default route.
     *
     * <p>Not every interface: see the class comment. Even this one is only a
     * single signal, so a customer who swaps a network card still matches on the
     * other four.
     */
    static Optional<String> primaryMacAddress() {
        try {
            InetAddress probe = InetAddress.getByName("1.1.1.1");
            NetworkInterface viaDefaultRoute = NetworkInterface.getByInetAddress(probe);
            if (viaDefaultRoute != null && viaDefaultRoute.getHardwareAddress() != null) {
                return Optional.of(HexFormat.of().formatHex(viaDefaultRoute.getHardwareAddress()));
            }
        } catch (Exception ignored) {
            // No network at all is normal for an offline accounting machine.
        }

        // Fall back to the first non-loopback, non-virtual interface that is up.
        // Names are checked explicitly because isVirtual() cannot be trusted.
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp() || isKnownVirtual(ni.getName())) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    return Optional.of(HexFormat.of().formatHex(mac));
                }
            }
        } catch (Exception ignored) {
            // Treated as an unavailable signal.
        }
        return Optional.empty();
    }

    /** Interface names belonging to virtualisation, container and VPN software. */
    static boolean isKnownVirtual(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("docker")
                || lower.startsWith("veth")
                || lower.startsWith("br-")
                || lower.startsWith("virbr")
                || lower.startsWith("vmnet")
                || lower.startsWith("vboxnet")
                || lower.startsWith("tun")
                || lower.startsWith("tap")
                || lower.startsWith("utun")
                || lower.startsWith("zt")
                || lower.startsWith("wg")
                || lower.contains("vethernet")
                || lower.contains("hyper-v");
    }

    private static String hash(String label, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(label.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
