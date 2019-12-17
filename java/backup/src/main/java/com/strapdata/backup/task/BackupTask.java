package com.strapdata.backup.task;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.microsoft.azure.storage.StorageException;
import com.strapdata.backup.BackupException;
import com.strapdata.backup.uploader.FilesUploader;
import com.strapdata.backup.util.Directories;
import com.strapdata.backup.util.GlobalLock;
import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.CloudStorageSecret;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;
import javax.naming.ConfigurationException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Adler32;

import static java.lang.Math.toIntExact;

public class BackupTask {
    private static final Logger logger = LoggerFactory.getLogger(BackupTask.class);
    
    // Ver. 2.0 = com-recovery_codes-jb-1-Data.db
    // Ver. 2.1 = lb-1-big-Data.db
    // Ver. 2.2 = lb-1-big-Data.db
    // Ver. 3.0 = mc-1-big-Data.db
    private static final Pattern SSTABLE_RE = Pattern.compile("((?:[a-zA-Z0-9][a-zA-Z0-9_-]+[a-zA-Z0-9][a-zA-Z0-9_-]+-)?[a-z]{2}-(\\d+)(?:-big)?)-.*");
    private static final int SSTABLE_PREFIX_IDX = 1;
    private static final int SSTABLE_GENERATION_IDX = 2;
    private static final ImmutableList<String> DIGESTS = ImmutableList.of("crc32", "adler32", "sha1");
    // ver 2.0 sha1 file includes checksum and space separated partial filepath
    private static final Pattern CHECKSUM_RE = Pattern.compile("^([a-zA-Z0-9]+).*");
    
    private final JMXServiceURL cassandraJMXServiceURL;
    private final FilesUploader filesUploader;
    private final Path snapshotManifestDirectory;
    private final Path snapshotTokensDirectory;
    private final GlobalLock globalLock;
    
    private final Path cassandraDataDirectory;
    
    private final Path backupManifestsRootKey;
    private final Path backupTokensRootKey;
    
    private final BackupArguments arguments;
    
    private final StorageServiceMBean storageServiceMBean;

    private Thread snapshotCleaner = null;
    
    static class KeyspaceColumnFamilySnapshot {
        final String keyspace, columnFamily;
        final Path snapshotDirectory;
        
        public KeyspaceColumnFamilySnapshot(final Path snapshotDirectory) {
            // /data /<keyspace> /<column family> /snapshots /<snapshot>
            
            final Path columnFamilyDirectory = snapshotDirectory.getParent().getParent();
            
            this.columnFamily = columnFamilyDirectory.getFileName().toString();
            this.keyspace = columnFamilyDirectory.getParent().getFileName().toString();
            this.snapshotDirectory = snapshotDirectory;
        }
    }
    
    public BackupTask(final BackupArguments arguments,
                      final GlobalLock globalLock, StorageServiceMBean storageServiceMBean)
            throws IOException, StorageException, ConfigurationException, URISyntaxException, InvalidKeyException {
        this.cassandraJMXServiceURL = arguments.jmxServiceURL;
        this.snapshotManifestDirectory = arguments.sharedContainerPath.resolve(Paths.get("cassandra-operator/manifests"));
        this.snapshotTokensDirectory = arguments.sharedContainerPath.resolve(Paths.get("cassandra-operator/tokens"));
        this.globalLock = globalLock;
        this.storageServiceMBean = storageServiceMBean;
        
        Files.createDirectories(snapshotManifestDirectory);
        Files.createDirectories(snapshotTokensDirectory);
        
        this.cassandraDataDirectory = arguments.cassandraDirectory.resolve(Directories.CASSANDRA_DATA);
        
        this.backupManifestsRootKey = Paths.get("manifests");
        this.backupTokensRootKey = Paths.get("tokens");
        
        this.filesUploader = new FilesUploader(arguments);
        this.arguments = arguments;
    }
    
    @VisibleForTesting
    public void takeCassandraSnapshot(final List<String> keyspaces, final String tag, final String columnFamily, final boolean drain) throws IOException, ExecutionException, InterruptedException {
        if (columnFamily != null) {
            final String keyspace = Iterables.getOnlyElement(keyspaces);
            
            logger.info("Taking snapshot \"{}\" on {}.{}.", tag, keyspace, columnFamily);
            // Currently only supported option by Cassandra during snapshot is to skipFlush
            // An empty map is used as skipping flush is currently not implemented.
            storageServiceMBean.takeTableSnapshot(keyspace, columnFamily, tag);
            
        } else {
            logger.info("Taking snapshot \"{}\" on {}.", tag, (keyspaces.isEmpty() ? "\"all\"" : keyspaces));
            storageServiceMBean.takeSnapshot(tag, keyspaces.toArray(new String[keyspaces.size()]));
        }
        
        // Optionally drain immediately following snapshot (e.g. pre-restore) - TODO: not sure of the "why" we do this here
        if (drain) {
            storageServiceMBean.drain();
        }
    }
    
    @VisibleForTesting
    public Collection<ManifestEntry> generateManifest(final List<String> keyspaces,
                                                      final String tag,
                                                      final Path cassandraDataDirectory) throws IOException {
        // find files belonging to snapshot
        final Map<String, ? extends Iterable<KeyspaceColumnFamilySnapshot>> snapshots = findKeyspaceColumnFamilySnapshots(cassandraDataDirectory);
        final Iterable<KeyspaceColumnFamilySnapshot> keyspaceColumnFamilySnapshots = snapshots.get(tag);
        
        if (keyspaceColumnFamilySnapshots == null) {
            if (!keyspaces.isEmpty()) {
                logger.warn("No keyspace column family snapshot directories were found for snapshot \"{}\" of {}", tag, Joiner.on(",").join(keyspaces));
                return new LinkedList<>();
            }
            
            // There should at least be system keyspace tables
            throw new BackupException(String.format("No keyspace column family snapshot directories were found for snapshot \"%s\" of all data.", tag));
        }
        
        // generate manifest (set of object keys and source files defining the snapshot)
        final Collection<ManifestEntry> manifest = new LinkedList<>(); // linked list to maintain order
        
        // add snapshot files to the manifest
        for (final KeyspaceColumnFamilySnapshot keyspaceColumnFamilySnapshot : keyspaceColumnFamilySnapshots) {
            final Path bucketKey = Paths.get(Directories.CASSANDRA_DATA).resolve(Paths.get(keyspaceColumnFamilySnapshot.keyspace, keyspaceColumnFamilySnapshot.columnFamily));
            Iterables.addAll(manifest, ssTableManifest(keyspaceColumnFamilySnapshot.snapshotDirectory, bucketKey));
        }
        
        logger.debug("{} files in manifest for snapshot \"{}\".", manifest.size(), tag);
        
        if (manifest.stream().noneMatch((Predicate<ManifestEntry>) input -> input != null && input.localFile.toString().contains("-Data.db"))) {
            throw new BackupException("No Data.db SSTables found in manifest. Aborting com.com.backup.");
        }
        
        return manifest;
        
    }
    
    public final void doUpload(List<String> tokens) throws Exception {
        Collection<ManifestEntry> manifest = generateManifest(arguments.keyspaces, arguments.snapshotTag, cassandraDataDirectory);
        
        Iterables.addAll(manifest, saveTokenList(tokens));
        Iterables.addAll(manifest, saveManifest(manifest, snapshotManifestDirectory, arguments.snapshotTag));
        
        filesUploader.uploadOrFreshenFiles(manifest);
    }
    
    public final void performBackup() throws Exception {
        if (arguments.offlineSnapshot) {
            List<String> tokens = new ArrayList<>();
            doUpload(tokens);
        } else {
            try {
                doUpload(storageServiceMBean.getTokens());
            } finally {
                this.snapshotCleaner.run();
            }
        }
    }

    public final boolean performSnapshot() {
        boolean result = true;
        if (arguments.offlineSnapshot) {
            logger.debug("Offline Snapshot, skip snapshot processing");
        } else {
            HashMap<String, String[]> environment = null; //we can pass nulls to the jmxconnectorFactory
            if (arguments.jmxPassword != null && arguments.jmxUser != null) {
                environment = new HashMap<>();
                String[] credentials = new String[]{arguments.jmxUser, arguments.jmxPassword};
                environment.put(JMXConnector.CREDENTIALS, credentials);
            }

            // create a ShutdownHook to clean snapshot if something go wrong during the nominal process
            // TODO do we have to also clean Offline Snapshot ???
            this.snapshotCleaner = new ClearSnapshotRunnable(arguments.snapshotTag, this.storageServiceMBean);
            Runtime.getRuntime().addShutdownHook(this.snapshotCleaner);

            try {
                takeCassandraSnapshot(arguments.keyspaces, arguments.snapshotTag, arguments.columnFamily, arguments.drain);
            } catch (IOException | ExecutionException | InterruptedException ex) {
                logger.error("Snapshot '{}' failed on keyspaces/tables '{}/{}'", arguments.snapshotTag, arguments.keyspaces, arguments.columnFamily, ex);
                result = false;
            }
        }
        return result;
    }

    @VisibleForTesting
    public static Map<String, List<Path>> listSSTables(Path table) throws IOException {
        return Files.list(table)
                .filter(path -> SSTABLE_RE.matcher(path.getFileName().toString()).matches())
                .collect(Collectors.groupingBy(path -> {
                    Matcher matcher = SSTABLE_RE.matcher(path.getFileName().toString());
                    //noinspection ResultOfMethodCallIgnored
                    matcher.matches();
                    return matcher.group(SSTABLE_GENERATION_IDX);
                }));
    }

    @VisibleForTesting
    public static String calculateChecksum(final Path filePath) throws IOException {
        try (final FileChannel fileChannel = FileChannel.open(filePath)) {

            int bytesStart;
            int bytesPerChecksum = 10 * 1024 * 1024;

            // Get last 10 megabytes of file to use for checksum
            if (fileChannel.size() >= bytesPerChecksum) {
                bytesStart = toIntExact(fileChannel.size()) - bytesPerChecksum;
            } else {
                bytesStart = 0;
                bytesPerChecksum = (int) fileChannel.size();
            }

            fileChannel.position(bytesStart);
            final ByteBuffer bytesToChecksum = ByteBuffer.allocate(bytesPerChecksum);
            int bytesRead = fileChannel.read(bytesToChecksum, bytesStart);

            assert (bytesRead == bytesPerChecksum);

            // Adler32 because it's faster than SHA / MD5 and Cassandra uses it - https://issues.apache.org/jira/browse/CASSANDRA-5862
            final Adler32 adler32 = new Adler32();
            adler32.update(bytesToChecksum.array());

            return String.valueOf(adler32.getValue());
        }
    }

    public static String sstableHash(Path path) throws IOException {
        final Matcher matcher = SSTABLE_RE.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new BackupException("Can't compute SSTable hash for " + path + ": doesn't taste like sstable");
        }

        for (String digest : DIGESTS) {
            final Path digestPath = path.resolveSibling(matcher.group(SSTABLE_PREFIX_IDX) + "-Digest." + digest);
            if (!Files.exists(digestPath)) {
                continue;
            }

            final Matcher matcherChecksum = CHECKSUM_RE.matcher(new String(Files.readAllBytes(digestPath), StandardCharsets.UTF_8));
            if (matcherChecksum.matches()) {
                return matcher.group(SSTABLE_GENERATION_IDX) + "-" + matcherChecksum.group(1);
            }
        }

        // Ver. 2.0 doesn't create hash file, so do it ourselves
        try {
            final Path dataFilePath = path.resolveSibling(matcher.group(SSTABLE_PREFIX_IDX) + "-Data.db");
            logger.warn("No digest file found, generating checksum based on {}.", dataFilePath);
            return matcher.group(SSTABLE_GENERATION_IDX) + "-" + calculateChecksum(dataFilePath);
        } catch (IOException e) {
            throw new BackupException("Couldn't generate checksum for " + path.toString());
        }
    }

    public static Collection<ManifestEntry> ssTableManifest(Path tablePath, Path tableBackupPath) throws IOException {
        final Map<String, List<Path>> sstables = listSSTables(tablePath);

        final LinkedList<ManifestEntry> manifest = new LinkedList<>();
        for (Map.Entry<String, List<Path>> entry : sstables.entrySet()) {
            final String hash = sstableHash(entry.getValue().get(0));

            for (Path path : entry.getValue()) {
                final Path tableRelative = tablePath.relativize(path);
                final Path backupPath = tableBackupPath.resolve(hash).resolve(tableRelative);
                manifest.add(new ManifestEntry(backupPath, path, ManifestEntry.Type.FILE));
            }
        }
        return manifest;
    }

    private Iterable<ManifestEntry> saveManifest(final Iterable<ManifestEntry> manifest, Path snapshotManifestDirectory, String tag) throws IOException {
        final Path manifestFilePath = Files.createFile(snapshotManifestDirectory.resolve(tag));

        try (final Writer writer = Files.newBufferedWriter(manifestFilePath)) {
            for (final ManifestEntry manifestEntry : manifest) {
                writer.write(Joiner.on(' ').join(manifestEntry.size, manifestEntry.objectKey));
                writer.write('\n');
            }
        }

        manifestFilePath.toFile().deleteOnExit();

        return ImmutableList.of(new ManifestEntry(backupManifestsRootKey.resolve(manifestFilePath.getFileName()), manifestFilePath, ManifestEntry.Type.MANIFEST_FILE));
    }

    public void stopBackupTask() {
        filesUploader.executorService.shutdownNow();
    }

    private Iterable<ManifestEntry> saveTokenList(List<String> tokens) throws IOException {
        final Path tokensFilePath = snapshotTokensDirectory.resolve(String.format("%s-tokens.yaml", arguments.snapshotTag));

        try (final OutputStream stream = Files.newOutputStream(tokensFilePath); final PrintStream writer = new PrintStream(stream)) {
            writer.println("# automatically generated by cassandra-com.com.backup.");
            writer.println("# add the following to cassandra.yaml when restoring to a new cluster.");
            writer.printf("initial_token: %s%n", Joiner.on(',').join(tokens));
        }

        tokensFilePath.toFile().deleteOnExit();

        return ImmutableList.of(new ManifestEntry(backupTokensRootKey.resolve(tokensFilePath.getFileName()), tokensFilePath, ManifestEntry.Type.FILE));
    }

    private static Map<String, ? extends Iterable<KeyspaceColumnFamilySnapshot>> findKeyspaceColumnFamilySnapshots(final Path cassandraDataDirectory) throws IOException {
        // /var/lib/cassandra /data /<keyspace> /<column family> /snapshots /<snapshot>
        return Files.find(cassandraDataDirectory, 4, (path, basicFileAttributes) -> path.getParent().endsWith("snapshots"))
                .map((KeyspaceColumnFamilySnapshot::new))
                .collect(Collectors.groupingBy(k -> k.snapshotDirectory.getFileName().toString()));
    }

    public BackupArguments getArguments() {
        return arguments;
    }

    private static class ClearSnapshotRunnable extends Thread {
        private final String snapshotTag;
        private final StorageServiceMBean storageServiceMBean;

        private boolean hasRun = false;

        public ClearSnapshotRunnable(String snapshotTag, StorageServiceMBean storageServiceMBean) {
            this.snapshotTag = snapshotTag;
            this.storageServiceMBean = storageServiceMBean;
        }

        @Override
        public void run() {
            if (hasRun)
                return;

            hasRun = true;
            try {
                storageServiceMBean.clearSnapshot(snapshotTag);
                logger.info("Cleared snapshot \"{}\".", snapshotTag);
                // remove ShutdownHook for this snapshot to avoid resource leak
                Runtime.getRuntime().removeShutdownHook(this);
            } catch (final IOException e) {
                logger.error("Failed to cleanup snapshot {}.", snapshotTag, e);
            }
        }
    }
}