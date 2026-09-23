import io.juicefs.JuiceFileSystem;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;

/** The old Hadoop facade on the same native cache and identity settings. */
public final class OldRead {
    public static void main(String[] args) throws Exception {
        UserGroupInformation.createUserForTesting("hdfs", new String[] {"supergroup"})
                .doAs((PrivilegedExceptionAction<Void>) () -> {
                    Configuration conf = new Configuration(false);
                    conf.set("juicefs.meta", System.getenv("AGENTFS_META"));
                    conf.set("juicefs.cache-dir", "memory");
                    conf.set("juicefs.cache-size", "100");
                    conf.set("juicefs.memory-size", "300");
                    conf.set("juicefs.no-usage-report", "true");
                    try (JuiceFileSystem files = new JuiceFileSystem()) {
                        files.initialize(new URI("jfs://" + System.getenv("AGENTFS_VOLUME")), conf);
                        byte[] expected = new byte[4096];
                        Arrays.fill(expected, (byte) 97);
                        byte[] actual = new byte[4096];
                        try (FSDataInputStream stream = files.open(new Path(args[0]))) {
                            stream.readFully(0, actual);
                        }
                        if (!Arrays.equals(expected, actual)) {
                            throw new AssertionError("Unexpected synthetic file bytes");
                        }
                        System.out.println("READY 4096");
                        System.out.flush();
                        System.in.read();
                    }
                    return null;
                });
    }
}
