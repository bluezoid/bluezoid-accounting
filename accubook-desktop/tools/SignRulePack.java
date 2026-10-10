import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Signs a rule-pack text file. Development use only - run as a single-file program:
 *
 *   java tools/SignRulePack.java <pack.txt> <key-id> <private-key-file> <out.rulepack>
 *
 * The private key never enters the repository. A release signs with the release
 * keys on the release machine (V-1).
 */
public class SignRulePack {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: SignRulePack <pack.txt> <key-id> <private-key-file> <out.rulepack>");
            System.exit(2);
        }
        String body = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        byte[] keyBytes = Base64.getDecoder().decode(Files.readString(Path.of(args[2])).strip());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes)));
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        Files.writeString(Path.of(args[3]), "accubook-rulepack-signature/1\nkey=" + args[1] + "\nsignature="
                + signature + "\n---\n" + body, StandardCharsets.UTF_8);
        System.out.println("signed " + args[0] + " -> " + args[3]);
    }
}
