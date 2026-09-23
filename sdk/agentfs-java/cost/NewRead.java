import io.juicefs.agentfs.AgentFS;
import java.util.Arrays;
import java.util.List;

/** One local synthetic-file sample; stdout is reserved for the readiness marker. */
public final class NewRead {
    public static void main(String[] args) throws Exception {
        try (AgentFS fs = AgentFS.builder(System.getenv("AGENTFS_VOLUME"),
                System.getenv("AGENTFS_META")).identity("hdfs", List.of("supergroup")).open()) {
            byte[] expected = new byte[4096];
            Arrays.fill(expected, (byte) 97);
            if (args.length == 2 && args[1].equals("seed")) {
                fs.writeFile(args[0], expected);
                return;
            }
            if (!Arrays.equals(expected, fs.readFile(args[0], 0, 4096).data)) {
                throw new AssertionError("Unexpected synthetic file bytes");
            }
            System.out.println("READY 4096");
            System.out.flush();
            System.in.read();
        }
    }
}
